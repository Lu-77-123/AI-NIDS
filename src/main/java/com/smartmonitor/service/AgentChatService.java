package com.smartmonitor.service;

import com.smartmonitor.entity.AgentAction;
import com.smartmonitor.entity.AlertInfo;
import com.smartmonitor.entity.Device;
import com.smartmonitor.entity.TrafficData;
import com.smartmonitor.repository.AgentActionRepository;
import com.smartmonitor.repository.AlertInfoRepository;
import com.smartmonitor.repository.DeviceRepository;
import com.smartmonitor.repository.TrafficDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能体「小七」对话服务 — 统一由 DeepSeek V4.1-Flash 大模型驱动。
 *
 * 系统提示词中注入设备/告警/决策/流量实时业务快照，模型基于快照回答。
 * 已移除本地关键词规则引擎：未配置 Key 或调用失败时直接返回明确错误，不再静默兜底。
 */
@Service
public class AgentChatService {

    private static final Logger logger = LoggerFactory.getLogger(AgentChatService.class);
    private static final long ONLINE_WINDOW_SECONDS = 30;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final AgentActionRepository agentActionRepository;
    private final AlertInfoRepository alertInfoRepository;
    private final DeviceRepository deviceRepository;
    private final TrafficDataRepository trafficDataRepository;
    private final DeepSeekService deepSeekService;

    public AgentChatService(AgentActionRepository agentActionRepository,
                            AlertInfoRepository alertInfoRepository,
                            DeviceRepository deviceRepository,
                            TrafficDataRepository trafficDataRepository,
                            DeepSeekService deepSeekService) {
        this.agentActionRepository = agentActionRepository;
        this.alertInfoRepository = alertInfoRepository;
        this.deviceRepository = deviceRepository;
        this.trafficDataRepository = trafficDataRepository;
        this.deepSeekService = deepSeekService;
    }

    /**
     * 处理一条用户提问 — 全部交由 DeepSeek V4.1-Flash 回答。
     * @return {reply, intent, suggestions, engine, model, ok}
     */
    public Map<String, Object> chat(String message) {
        String q = message == null ? "" : message.trim();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggestions", defaultSuggestions());
        result.put("engine", "deepseek");
        result.put("model", deepSeekService.getModel());

        // 空提问属于输入校验，不消耗 API
        if (q.isEmpty()) {
            result.put("reply", "请输入你想问的问题，例如：现在哪些设备离线了？");
            result.put("intent", "help");
            result.put("ok", true);
            return result;
        }

        if (!deepSeekService.isEnabled()) {
            result.put("reply", "AI 助手未启用：未检测到 DEEPSEEK_API_KEY。请在项目根目录 .env 中填入 Key 后重启后端。");
            result.put("intent", "error");
            result.put("ok", false);
            return result;
        }

        try {
            List<Map<String, String>> msgs = new ArrayList<>();
            msgs.add(msg("system", buildSystemPrompt()));
            msgs.add(msg("user", q));
            String reply = deepSeekService.chat(msgs);
            result.put("reply", reply);
            result.put("intent", "ai");
            result.put("ok", true);
            logger.info("小七问答 engine=deepseek model={}, q={}", deepSeekService.getModel(), q);
        } catch (Exception e) {
            logger.warn("DeepSeek 问答失败: {}", e.getMessage());
            result.put("reply", "AI 助手暂时不可用：" + e.getMessage() + "。请检查网络或 API Key 后稍后重试。");
            result.put("intent", "error");
            result.put("ok", false);
        }
        return result;
    }

    private Map<String, String> msg(String role, String content) {
        Map<String, String> m = new HashMap<>(2);
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private List<String> defaultSuggestions() {
        return new ArrayList<>(Arrays.asList(
                "现在哪些设备离线了？", "最近有什么攻击？", "小七给了哪些处理建议？", "全网流量怎么样？"));
    }

    /**
     * 拼装大模型系统提示词：角色设定 + 实时业务快照（设备/告警/决策/流量）
     */
    private String buildSystemPrompt() {
        return "你叫「小七」，是梧州学院网络入侵检测系统里的安全决策助手，7×24 小时值守一套 " + deviceRepository.count() + " 台网络设备的环境。"
                + "请严格依据下面【实时数据快照】回答用户问题，数据里没有的信息不要编造；"
                + "用简体中文，口语化、简洁有条理（100~250字，复杂问题可用短句或编号列点）；"
                + "涉及处置建议要具体可执行；你背后的大语言模型是 deepseek V4（" + deepSeekService.getModel() + "），但流量异常检测模型是统计基线（中位数+MAD 稳健基线），不要混淆。\n\n"
                + buildBusinessSnapshot();
    }

    /** 实时业务快照：供大模型回答时引用 */
    private String buildBusinessSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("【实时数据快照】时间：").append(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append('\n');

        // 设备状态
        long total = deviceRepository.count();
        Map<Long, TrafficData> latest = latestMap();
        long online = latest.values().stream().filter(this::isOnline).count();
        long anomaly = latest.values().stream()
                .filter(t -> t.getIsAnomaly() != null && t.getIsAnomaly() == 1).count();
        sb.append("设备：共 ").append(total).append(" 台，在线 ").append(online)
                .append(" 台，离线 ").append(total - online).append(" 台，异常标记 ").append(anomaly).append(" 台。\n");
        List<String> offlineNames = new ArrayList<>();
        for (Device d : deviceRepository.findAll()) {
            TrafficData t = latest.get(d.getId());
            if (!isOnline(t)) {
                offlineNames.add((d.getRemark() != null ? d.getRemark() : d.getIp()) + "(" + d.getIp() + ")");
            }
        }
        if (!offlineNames.isEmpty()) sb.append("离线设备：").append(String.join("、", offlineNames)).append('\n');

        // 告警
        LocalDateTime todayStart = java.time.LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        Long todayCount = alertInfoRepository.countAfter(todayStart);
        sb.append("今日告警：").append(todayCount).append(" 条");
        List<Object[]> typeCounts = alertInfoRepository.countByAttackType(todayStart);
        if (!typeCounts.isEmpty()) {
            String dist = typeCounts.stream()
                    .sorted((a, b) -> Long.compare((Long) b[1], (Long) a[1]))
                    .limit(4)
                    .map(o -> attackLabel((String) o[0]) + " " + o[1])
                    .collect(Collectors.joining("、"));
            sb.append("（").append(dist).append("）");
        }
        sb.append('。');
        List<AlertInfo> recent3 = alertInfoRepository.findTop50ByOrderByAlertTimeDesc().stream()
                .filter(a -> a.getAlertTime() != null && a.getAlertTime().isAfter(todayStart.minusHours(12)))
                .limit(3).collect(Collectors.toList());
        if (!recent3.isEmpty()) {
            sb.append("最近告警：");
            for (AlertInfo a : recent3) {
                sb.append(String.format("\n· %s %s %s（%s）%n",
                        a.getAlertTime().format(HM), a.getDeviceName(),
                        attackLabel(a.getAttackType()), severityLabel(a.getSeverity())));
            }
        }

        // 待处理决策
        List<AgentAction> pending = agentActionRepository.findByStatusOrderByActionTimeDesc(0);
        sb.append("待处理决策：").append(pending.size()).append(" 条");
        if (!pending.isEmpty()) {
            String byType = pending.stream()
                    .collect(Collectors.groupingBy(AgentAction::getActionType, Collectors.counting()))
                    .entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(e -> actionLabel(e.getKey()) + " " + e.getValue() + " 条")
                    .collect(Collectors.joining("、"));
            sb.append("（").append(byType).append("）");
        }
        sb.append('。');

        // 流量
        long totalIn = latest.values().stream().filter(this::isOnline).mapToLong(t -> nz(t.getInBps())).sum();
        long totalOut = latest.values().stream().filter(this::isOnline).mapToLong(t -> nz(t.getOutBps())).sum();
        sb.append("全网实时流量：入站 ").append(formatBps(totalIn))
                .append("，出站 ").append(formatBps(totalOut)).append("。\n");
        latest.values().stream().filter(this::isOnline)
                .sorted(Comparator.comparingLong((TrafficData t) -> nz(t.getInBps()) + nz(t.getOutBps())).reversed())
                .limit(3)
                .forEach(t -> sb.append(String.format("· %s 入%s/出%s%s\n",
                        t.getDeviceName(), formatBps(nz(t.getInBps())), formatBps(nz(t.getOutBps())),
                        t.getIsAnomaly() != null && t.getIsAnomaly() == 1 ? "（异常）" : "")));
        return sb.toString();
    }


    // ===== 工具方法 =====

    /** 在线设备快照：deviceId -> 最新流量记录 */
    private Map<Long, TrafficData> latestMap() {
        return trafficDataRepository.findLatestForAllDevices().stream()
                .collect(Collectors.toMap(TrafficData::getDeviceId, t -> t, (a, b) -> a, HashMap::new));
    }

    private boolean isOnline(TrafficData td) {
        return td != null && td.getCollectTime() != null
                && td.getCollectTime().isAfter(LocalDateTime.now().minusSeconds(ONLINE_WINDOW_SECONDS));
    }

    private long nz(Long v) {
        return v == null ? 0L : v;
    }

    private String formatBps(long bps) {
        if (bps >= 1_000_000_000L) return String.format("%.2f Gbps", bps / 1_000_000_000.0);
        if (bps >= 1_000_000L) return String.format("%.2f Mbps", bps / 1_000_000.0);
        if (bps >= 1_000L) return String.format("%.2f Kbps", bps / 1_000.0);
        return bps + " bps";
    }

    private String attackLabel(String code) {
        if (code == null) return "未知异常";
        switch (code) {
            case "DDoS": return "DDoS 攻击";
            case "WORM_SPREAD": return "蠕虫传播";
            case "DATA_EXFIL": return "数据外泄";
            case "PORT_SCAN": return "端口扫描";
            case "SLOW_DOS": return "慢速 DoS";
            case "ARP_SPOOF": return "ARP 欺骗";
            case "UNKNOWN": return "未知异常";
            default: return code;
        }
    }

    private String severityLabel(String s) {
        if (s == null) return "未知";
        switch (s) {
            case "critical": return "严重";
            case "high": return "高危";
            case "medium": return "中危";
            default: return "低危";
        }
    }

    private String actionLabel(String type) {
        switch (type) {
            case "ISOLATE": return "建议隔离";
            case "DISCONNECT": return "建议断网";
            case "ADJUST_THRESHOLD": return "调整阈值";
            case "ALERT_ESCALATE": return "告警升级";
            case "RETRAIN_MODEL": return "模型重训";
            default: return type;
        }
    }
}
