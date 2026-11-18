package com.smartmonitor.service;

import com.smartmonitor.entity.AlertInfo;
import com.smartmonitor.repository.AlertInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AlertService {

    private static final Logger logger = LoggerFactory.getLogger(AlertService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AlertInfoRepository alertInfoRepository;
    private final AttackIntentClassifier classifier;
    private final DeepSeekService deepSeekService;
    private final AlertEvidenceCollector evidenceCollector;
    private final AnalysisValidator analysisValidator;

    public AlertService(AlertInfoRepository alertInfoRepository, AttackIntentClassifier classifier,
                        DeepSeekService deepSeekService, AiConfigService aiConfigService,
                        AlertEvidenceCollector evidenceCollector, AnalysisValidator analysisValidator) {
        this.alertInfoRepository = alertInfoRepository;
        this.classifier = classifier;
        this.deepSeekService = deepSeekService;
        this.aiConfigService = aiConfigService;
        this.evidenceCollector = evidenceCollector;
        this.analysisValidator = analysisValidator;
    }

    /**
     * 创建入侵告警
     */
    /** 告警冷却：同设备同攻击类型在窗口期内只告警一次（运行期可在 AI 配置中心调整） */
    private final AiConfigService aiConfigService;

    private final Map<String, LocalDateTime> lastAlertAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** 告警冷却窗口（分钟），供监控页展示 */
    public long getCooldownMinutes() {
        return aiConfigService.get().getCooldownMinutes();
    }

    public AlertInfo createAlert(Long deviceId, String deviceName, String deviceIp,
                                  long inBps, long outBps, double anomalyScore) {
        Map<String, Object> intent = classifier.classify(inBps, outBps, anomalyScore);

        // 冷却抑制：避免基线抖动造成的重复告警风暴
        String key = deviceId + "|" + intent.get("attackType");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last = lastAlertAt.get(key);
        if (last != null && last.plusMinutes(getCooldownMinutes()).isAfter(now)) {
            logger.info("告警冷却中，跳过: 设备={}, 类型={}", deviceName, intent.get("attackType"));
            return null;
        }
        lastAlertAt.put(key, now);

        AlertInfo alert = new AlertInfo();
        alert.setDeviceId(deviceId);
        alert.setDeviceName(deviceName);
        alert.setDeviceIp(deviceIp);
        alert.setAlertTime(LocalDateTime.now());
        alert.setAttackType((String) intent.get("attackType"));
        alert.setSeverity((String) intent.get("severity"));
        alert.setAnomalyScore(anomalyScore);
        alert.setConfidence((Double) intent.get("confidence"));
        alert.setInBps(inBps);
        alert.setOutBps(outBps);
        alert.setDescription((String) intent.get("description"));
        alert.setStatus(0);

        AlertInfo saved = alertInfoRepository.save(alert);
        logger.warn("入侵告警: 设备={}, 类型={}, 等级={}, 评分={}",
                deviceName, intent.get("attackType"), intent.get("severity"), anomalyScore);
        return saved;
    }

    /** 查询最近告警 */
    public List<AlertInfo> getRecentAlerts() {
        return alertInfoRepository.findTop50ByOrderByAlertTimeDesc();
    }

    /** 按状态查询 */
    public List<AlertInfo> getByStatus(Integer status) {
        return alertInfoRepository.findByStatusOrderByAlertTimeDesc(status);
    }

    /** 处理告警 */
    public AlertInfo handleAlert(Long id, Integer status, String note) {
        AlertInfo alert = alertInfoRepository.findById(id).orElse(null);
        if (alert != null) {
            alert.setStatus(status);
            alert.setHandleNote(note);
            alert.setHandleTime(LocalDateTime.now());
            return alertInfoRepository.save(alert);
        }
        return null;
    }

    /** 删除单条告警（仅管理员，清理演示/测试数据用） */
    public boolean deleteAlert(Long id) {
        if (id == null || !alertInfoRepository.existsById(id)) {
            return false;
        }
        alertInfoRepository.deleteById(id);
        logger.info("删除告警: alertId={}", id);
        return true;
    }

    /** 告警统计 */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);

        Long todayCount = alertInfoRepository.countAfter(todayStart);
        stats.put("todayCount", todayCount != null ? todayCount : 0);

        List<AlertInfo> all = alertInfoRepository.findTop50ByOrderByAlertTimeDesc();
        long unhandled = all.stream().filter(a -> a.getStatus() == 0).count();
        stats.put("unhandledCount", unhandled);

        // 威胁等级统计
        long critical = all.stream().filter(a -> "critical".equals(a.getSeverity())).count();
        long high = all.stream().filter(a -> "high".equals(a.getSeverity())).count();
        long medium = all.stream().filter(a -> "medium".equals(a.getSeverity())).count();
        long low = all.stream().filter(a -> "low".equals(a.getSeverity())).count();
        stats.put("critical", critical);
        stats.put("high", high);
        stats.put("medium", medium);
        stats.put("low", low);

        // 攻击类型统计
        List<Object[]> typeStats = alertInfoRepository.countByAttackType(todayStart);
        Map<String, Long> typeMap = new HashMap<>();
        for (Object[] row : typeStats) {
            typeMap.put((String) row[0], (Long) row[1]);
        }
        stats.put("attackTypes", typeMap);

        return stats;
    }

    private String attackLabel(String code) {
        if (code == null) return "未知";
        switch (code) {
            case "DDoS": return "DDoS 攻击";
            case "端口扫描": return "端口扫描";
            case "蠕虫传播": return "蠕虫病毒横向传播";
            case "数据外泄": return "数据外泄";
            case "慢速DoS": return "慢速 DoS 攻击";
            case "未知异常": return "未知异常流量";
            default: return code;
        }
    }

    private String severityLabel(String code) {
        if (code == null) return "未知";
        switch (code) {
            case "critical": return "紧急";
            case "high": return "高危";
            case "medium": return "中危";
            case "low": return "低危";
            default: return code;
        }
    }

    private String formatBps(long bps) {
        if (bps >= 1_000_000_000L) return String.format("%.2f Gbps", bps / 1_000_000_000.0);
        if (bps >= 1_000_000L) return String.format("%.2f Mbps", bps / 1_000_000.0);
        if (bps >= 1_000L) return String.format("%.2f Kbps", bps / 1_000.0);
        return bps + " bps";
    }

    /**
     * 对单条告警调用 DeepSeek V4.1-Flash 进行 AI 研判。
     * 已研判过的告警直接返回缓存结果（避免重复调用产生费用）。
     * 大模型为唯一研判引擎：未配置 Key 或调用失败时返回明确错误，不再使用规则结论兜底。
     */
    public Map<String, Object> analyzeWithAi(Long id) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);

        AlertInfo alert = alertInfoRepository.findById(id).orElse(null);
        if (alert == null) {
            result.put("analysis", "未找到该告警记录。");
            result.put("ai", false);
            result.put("ok", false);
            return result;
        }

        if (alert.getAiAnalysis() != null && !alert.getAiAnalysis().trim().isEmpty()) {
            result.put("analysis", alert.getAiAnalysis());
            result.put("ai", true);
            result.put("ok", true);
            result.put("cached", true);
            result.put("model", deepSeekService.getModel());
            result.put("analysisTime", alert.getAiAnalysisTime() != null
                    ? alert.getAiAnalysisTime().format(DT_FMT) : null);
            return result;
        }

        if (!deepSeekService.isEnabled()) {
            result.put("analysis", "AI 研判不可用：未检测到 DEEPSEEK_API_KEY。请在项目根目录 .env 中填入 Key 后重启后端。");
            result.put("ai", false);
            result.put("ok", false);
            logger.warn("告警 AI 研判中止(未配置Key): alertId={}", id);
            return result;
        }

        String systemPrompt = "你是一名资深网络安全分析师，正在研判网络入侵检测系统产生的告警。"
                + "你必须只依据用户提供的【系统取证结果】作答，不得臆测未提供的信息。\n"
                + "只输出一个 JSON 对象，不要输出任何解释性文字，字段如下：\n"
                + "{\"threat_level\":\"real 或 suspicious 或 false_positive\","
                + "\"confidence\":0到1之间的小数,"
                + "\"reasoning\":\"一句话判断依据\","
                + "\"evidence\":[\"引用取证结果中的事实，最多5条\"],"
                + "\"causes\":[\"可能成因，1-2条\"],"
                + "\"actions\":[\"可立即执行的处置建议，2-3条\"]}\n"
                + "判定标准：流量突增且持续、当前值创窗口新高、设备为核心出口，倾向 real；"
                + "单点抖动、幅度小、无历史告警，倾向 false_positive；介于两者之间用 suspicious。";

        // 取证：把可核实的客观事实交给模型，避免它仅凭四个字段臆断
        AlertEvidenceCollector.Evidence evidence = evidenceCollector.collect(alert);

        StringBuilder user = new StringBuilder();
        user.append("告警数据如下：\n");
        user.append("设备名称：").append(alert.getDeviceName()).append('\n');
        user.append("设备 IP：").append(alert.getDeviceIp()).append('\n');
        user.append("告警时间：").append(alert.getAlertTime() != null ? alert.getAlertTime().format(DT_FMT) : "-").append('\n');
        user.append("攻击类型：").append(attackLabel(alert.getAttackType()))
                .append("（原始分类码：").append(alert.getAttackType()).append("）\n");
        user.append("威胁等级：").append(severityLabel(alert.getSeverity())).append('\n');
        user.append(String.format("异常评分：%.2f（0-1，越高越可疑）%n", alert.getAnomalyScore()));
        user.append(String.format("模型置信度：%.0f%%%n", alert.getConfidence() * 100));
        user.append("入站流量：").append(formatBps(alert.getInBps() != null ? alert.getInBps() : 0L)).append('\n');
        user.append("出站流量：").append(formatBps(alert.getOutBps() != null ? alert.getOutBps() : 0L)).append('\n');
        user.append("系统描述：").append(alert.getDescription() != null ? alert.getDescription() : "-");
        user.append("\n\n").append(evidence.toPromptText());
        user.append("\n\n输出要求：只输出一个 JSON 对象；threat_level 与 confidence 必须自洽（判 real 时置信度不低于 0.5）；")
            .append("evidence 至少引用 1 条取证事实并带上序号。");

        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> sys = new HashMap<>();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        Map<String, String> usr = new HashMap<>();
        usr.put("role", "user");
        usr.put("content", user.toString());
        messages.add(sys);
        messages.add(usr);

        try {
            // 第一次调用：强制 JSON 输出，便于字段级校验
            String raw = deepSeekService.chatJson(messages);
            AnalysisValidator.Result vr = analysisValidator.validate(raw, alert, evidence);

            // 校验不通过：带着具体问题让模型重写一次（仅一次，避免无限消耗）
            if (!vr.valid) {
                logger.warn("研判输出校验未通过，触发重试: alertId={}, errors={}", id, vr.errors);
                List<Map<String, String>> retry = new ArrayList<>(messages);
                Map<String, String> prev = new HashMap<>();
                prev.put("role", "assistant");
                prev.put("content", raw);
                retry.add(prev);
                Map<String, String> fix = new HashMap<>();
                fix.put("role", "user");
                fix.put("content", "你的上次输出存在以下问题：" + String.join("；", vr.errors)
                        + "。请修正后严格按 JSON 格式重新输出，只输出 JSON 对象。");
                retry.add(fix);
                String raw2 = deepSeekService.chatJson(retry);
                vr = analysisValidator.validate(raw2, alert, evidence);
            }

            boolean validated = vr.valid;
            String analysis;
            if (validated) {
                analysis = vr.rendered;
            } else {
                // 两次都不合格：降级为纯证据摘要，明确标注未通过校验，不展示可能误导的模型结论
                analysis = analysisValidator.renderFallback(alert, evidence, vr.errors);
                logger.warn("研判输出两次校验均未通过，降级为证据摘要: alertId={}, errors={}", id, vr.errors);
            }

            alert.setAiAnalysis(analysis);
            alert.setAiAnalysisTime(LocalDateTime.now());
            alertInfoRepository.save(alert);

            result.put("analysis", analysis);
            result.put("ai", true);
            result.put("ok", true);
            result.put("cached", false);
            result.put("validated", validated);
            if (vr.level != null) result.put("threatLevel", vr.level);
            if (vr.confidence > 0) result.put("modelConfidence", vr.confidence);
            if (!vr.errors.isEmpty()) result.put("validationErrors", vr.errors);
            result.put("model", deepSeekService.getModel());
            result.put("analysisTime", alert.getAiAnalysisTime().format(DT_FMT));
            logger.info("告警 AI 研判成功: alertId={}, model={}, validated={}", id, deepSeekService.getModel(), validated);
        } catch (Exception e) {
            String errMsg = "AI 研判失败（" + e.getMessage() + "）。本次未使用任何本地规则结论，请稍后重试或检查 DeepSeek 服务/网络。";
            result.put("analysis", errMsg);
            result.put("ai", false);
            result.put("ok", false);
            logger.warn("告警 AI 研判失败: alertId={}, err={}", id, e.getMessage());
        }
        return result;
    }
}
