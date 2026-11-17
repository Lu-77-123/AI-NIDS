package com.smartmonitor.service;

import com.smartmonitor.entity.AgentAction;
import com.smartmonitor.entity.AlertInfo;
import com.smartmonitor.repository.AgentActionRepository;
import com.smartmonitor.repository.AlertInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 智能体「小七」决策引擎 — 7×24 小时值守，根据告警历史和攻击模式自动生成响应决策。
 *
 * 决策规则：
 * 1. ISOLATE       — 同一设备 5 分钟内连续 3 次以上 DDoS/蠕虫 → 建议隔离
 * 2. DISCONNECT    — 同一设备数据外泄 critical → 建议断网
 * 3. ALERT_ESCALATE — 单次 critical 级别告警 → 告警升级通知（默认自动执行）
 * 4. RETRAIN_MODEL   — 10 分钟内全网 high/critical 告警 ≥10 条 → 模型重训
 *
 * 每条决策附带「小七」评估的置信度（0~1），供管理员研判参考。
 *
 * 自适应反馈：某类决策被管理员驳回得越多，该类的触发门槛越高、去重冷却越长
 * （驳回 1 次冷却 +5 分钟，封顶 30 分钟；隔离/重训门槛同步上调），形成人工反馈闭环。
 * 自动执行由 AgentPolicyService 中每类决策的开关决定。
 */
@Service
public class AgentDecisionEngine {

    private static final Logger logger = LoggerFactory.getLogger(AgentDecisionEngine.class);

    /** 智能体名称 */
    public static final String AGENT_NAME = "小七";

    /** 决策基础去重冷却（秒） */
    private static final int BASE_COOLDOWN_SECONDS = 300;
    /** 每次驳回增加的冷却（秒） */
    private static final int COOLDOWN_STEP_SECONDS = 300;
    /** 冷却上限（秒，30 分钟） */
    private static final int COOLDOWN_MAX_SECONDS = 1800;

    private final AgentActionRepository agentActionRepository;
    private final AlertInfoRepository alertInfoRepository;
    private final AgentPolicyService policyService;

    public AgentDecisionEngine(AgentActionRepository agentActionRepository,
                                AlertInfoRepository alertInfoRepository,
                                AgentPolicyService policyService) {
        this.agentActionRepository = agentActionRepository;
        this.alertInfoRepository = alertInfoRepository;
        this.policyService = policyService;
    }

    /**
     * 根据新告警评估并生成智能体决策
     * @param alert 新生成的告警
     * @return 生成的决策列表（可能为空）
     */
    public List<AgentAction> evaluate(AlertInfo alert) {
        List<AgentAction> actions = new ArrayList<>();
        if (alert == null) return actions;

        String attackType = alert.getAttackType();
        Long deviceId = alert.getDeviceId();
        String deviceName = alert.getDeviceName();
        String severity = alert.getSeverity();

        // ===== 规则1：连续 DDoS/蠕虫 → 建议隔离 =====
        // 攻击类型常量必须与 AttackIntentClassifier 的输出一致（DDoS / 蠕虫传播）
        if (("DDoS".equals(attackType) || "蠕虫传播".equals(attackType))
                && ("high".equals(severity) || "critical".equals(severity))) {
            LocalDateTime windowStart = LocalDateTime.now().minusMinutes(5);
            long recentCount = alertInfoRepository
                    .findByDeviceIdOrderByAlertTimeDesc(deviceId)
                    .stream()
                    .filter(a -> a.getAlertTime() != null && a.getAlertTime().isAfter(windowStart))
                    .filter(a -> attackType.equals(a.getAttackType()))
                    .count();

            // 驳回反哺：该类决策历史每被驳回 2 次，触发门槛 +1（3→封顶 5）
            long isolateThreshold = effectiveIsolateThreshold();

            if (recentCount >= isolateThreshold && !hasRecentAction(deviceId, "ISOLATE")) {
                // 连续命中次数越多，置信度越高：3 次 0.72，每多 1 次 +0.08，封顶 0.98
                double confidence = Math.min(0.72 + (recentCount - 3) * 0.08, 0.98);
                AgentAction action = createAction(deviceId, deviceName, "ISOLATE",
                        "critical",
                        String.format("【小七】设备 %s 在 5 分钟内连续 %d 次检测到 %s 攻击，建议立即隔离",
                                deviceName, recentCount, attackType),
                        String.format("{\"attackType\":\"%s\",\"recentCount\":%d,\"threshold\":%d,\"anomalyScore\":%.4f}",
                                attackType, recentCount, isolateThreshold, alert.getAnomalyScore()),
                        confidence);
                applyAutoPolicy(action);
                actions.add(action);
            }
        }

        // ===== 规则2：数据外泄 → 建议断网 =====
        // 分类器输出中文“数据外泄”，分级为 high（严重外泄）
        if ("数据外泄".equals(attackType)
                && ("critical".equals(severity) || "high".equals(severity))) {
            if (!hasRecentAction(deviceId, "DISCONNECT")) {
                AgentAction action = createAction(deviceId, deviceName, "DISCONNECT",
                        "critical",
                        String.format("【小七】设备 %s 检测到严重数据外泄行为（出站 %d bps），建议立即断网",
                                deviceName, alert.getOutBps() != null ? alert.getOutBps() : 0),
                        String.format("{\"outBps\":%d,\"inBps\":%d,\"aiConfidence\":%.2f}",
                                alert.getOutBps() != null ? alert.getOutBps() : 0,
                                alert.getInBps() != null ? alert.getInBps() : 0,
                                alert.getConfidence() != null ? alert.getConfidence() : 0),
                        0.95);
                applyAutoPolicy(action);
                actions.add(action);
            }
        }

        // ===== 规则3：critical 告警 → 告警升级 =====
        if ("critical".equals(severity) && !hasRecentAction(deviceId, "ALERT_ESCALATE")) {
            AgentAction action = createAction(deviceId, deviceName, "ALERT_ESCALATE",
                    "high",
                    String.format("【小七】设备 %s 触发 critical 级别 %s 告警，已自动升级通知管理员",
                            deviceName, attackType),
                    String.format("{\"attackType\":\"%s\",\"anomalyScore\":%.4f}",
                            attackType, alert.getAnomalyScore() != null ? alert.getAnomalyScore() : 0),
                    0.88);
            // 是否自动执行由策略开关决定（默认开启）
            applyAutoPolicy(action);
            actions.add(action);
        }

        // ===== 规则4：全网高危告警密集 → 重训模型（门槛受驳回反哺影响） =====
        LocalDateTime last10Min = LocalDateTime.now().minusMinutes(10);
        List<AlertInfo> recentAlerts = alertInfoRepository.findTop50ByOrderByAlertTimeDesc();
        long recentCritical = recentAlerts.stream()
                .filter(a -> a.getAlertTime() != null && a.getAlertTime().isAfter(last10Min))
                .filter(a -> "critical".equals(a.getSeverity()) || "high".equals(a.getSeverity()))
                .count();
        long retrainThreshold = effectiveRetrainThreshold();
        if (recentCritical >= retrainThreshold && !hasRecentAction(null, "RETRAIN_MODEL")) {
            AgentAction action = createAction(null, "全网", "RETRAIN_MODEL",
                    "critical",
                    String.format("【小七】近 10 分钟内全网 high/critical 告警达 %d 条（触发门槛 %d），异常率过高，建议立即重训 AI 模型",
                            recentCritical, retrainThreshold),
                    String.format("{\"recentAlertCount\":%d,\"threshold\":%d,\"window\":\"10min\"}",
                            recentCritical, retrainThreshold),
                    0.80);
            applyAutoPolicy(action);
            actions.add(action);
        }

        // 保存决策
        for (AgentAction action : actions) {
            agentActionRepository.save(action);
            logger.info("智能体[{}]决策: 设备={}, 类型={}, 置信度={}, 原因={}",
                    AGENT_NAME, action.getDeviceName(), action.getActionType(),
                    action.getConfidence(), action.getReason());
        }

        return actions;
    }

    /** 查询所有决策记录 */
    public List<AgentAction> getAllActions() {
        return agentActionRepository.findAllByOrderByActionTimeDesc();
    }

    /** 按状态查询 */
    public List<AgentAction> getByStatus(Integer status) {
        return agentActionRepository.findByStatusOrderByActionTimeDesc(status);
    }

    /** 执行决策 */
    public AgentAction executeAction(Long id, String note) {
        AgentAction action = agentActionRepository.findById(id).orElse(null);
        if (action != null) {
            action.setStatus(1);
            action.setNote(note);
            action.setExecuteTime(LocalDateTime.now());
            return agentActionRepository.save(action);
        }
        return null;
    }

    /** 驳回决策 */
    public AgentAction rejectAction(Long id, String note) {
        AgentAction action = agentActionRepository.findById(id).orElse(null);
        if (action != null) {
            action.setStatus(2);
            action.setNote(note);
            action.setExecuteTime(LocalDateTime.now());
            return agentActionRepository.save(action);
        }
        return null;
    }

    /** 智能体统计 */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        List<AgentAction> all = agentActionRepository.findAllByOrderByActionTimeDesc();

        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        long todayCount = all.stream()
                .filter(a -> a.getActionTime() != null && a.getActionTime().isAfter(todayStart))
                .count();
        long pending = all.stream().filter(a -> a.getStatus() == 0).count();
        long executed = all.stream().filter(a -> a.getStatus() == 1 || a.getStatus() == 3).count();
        long rejected = all.stream().filter(a -> a.getStatus() == 2).count();

        stats.put("agentName", AGENT_NAME);
        stats.put("todayCount", todayCount);
        stats.put("pendingCount", pending);
        stats.put("executedCount", executed);
        stats.put("rejectedCount", rejected);
        stats.put("totalCount", all.size());

        // 自动执行数（status=3）
        long autoExecuted = all.stream().filter(a -> a.getStatus() == 3).count();
        stats.put("autoExecutedCount", autoExecuted);

        // 决策采纳率：已处理（执行/驳回/自动执行）中被执行的比例
        long processed = executed + rejected;
        double adoptionRate = processed == 0 ? 0.0
                : Math.round(executed * 10000.0 / processed) / 100.0;
        stats.put("adoptionRate", adoptionRate);

        // 平均置信度
        double avgConfidence = all.stream()
                .filter(a -> a.getConfidence() != null)
                .mapToDouble(AgentAction::getConfidence)
                .average().orElse(0.0);
        stats.put("avgConfidence", Math.round(avgConfidence * 100.0) / 100.0);

        // 最近一条决策摘要
        if (!all.isEmpty()) {
            AgentAction latest = all.get(0);
            Map<String, Object> latestInfo = new HashMap<>();
            latestInfo.put("reason", latest.getReason());
            latestInfo.put("actionType", latest.getActionType());
            latestInfo.put("actionTime", latest.getActionTime());
            latestInfo.put("status", latest.getStatus());
            stats.put("latest", latestInfo);
        }

        // 按决策类型明细：处理情况 + 当前策略 + 驳回反哺后的生效门槛/冷却
        List<Map<String, Object>> byType = new ArrayList<>();
        for (String type : AgentPolicyService.ACTION_TYPES) {
            long tPending = all.stream().filter(a -> type.equals(a.getActionType()) && a.getStatus() == 0).count();
            long tExecuted = all.stream().filter(a -> type.equals(a.getActionType()) && a.getStatus() == 1).count();
            long tAuto = all.stream().filter(a -> type.equals(a.getActionType()) && a.getStatus() == 3).count();
            long tRejected = all.stream().filter(a -> type.equals(a.getActionType()) && a.getStatus() == 2).count();
            long tProcessed = tExecuted + tAuto + tRejected;
            double tRate = tProcessed == 0 ? 0.0
                    : Math.round((tExecuted + tAuto) * 10000.0 / tProcessed) / 100.0;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("actionType", type);
            row.put("pending", tPending);
            row.put("executed", tExecuted);
            row.put("autoExecuted", tAuto);
            row.put("rejected", tRejected);
            row.put("adoptionRate", tRate);
            row.put("autoExecute", policyService.isAutoExecute(type));
            row.put("cooldownMin", cooldownSeconds(type) / 60);
            row.put("thresholdText", thresholdText(type));
            byType.add(row);
        }
        stats.put("byType", byType);

        return stats;
    }

    /** 各类型当前生效触发门槛（人类可读，供前端展示） */
    private String thresholdText(String type) {
        switch (type) {
            case "ISOLATE":
                return "5分钟内命中 " + effectiveIsolateThreshold() + " 次";
            case "DISCONNECT":
                return "数据外泄 critical 即触发";
            case "ALERT_ESCALATE":
                return "1 次 critical 告警";
            case "RETRAIN_MODEL":
                return "10分钟高危告警 " + effectiveRetrainThreshold() + " 条";
            case "ADJUST_THRESHOLD":
                return "全网异常率超阈值";
            default:
                return "-";
        }
    }

    /** 智能体「小七」档案 */
    public Map<String, Object> getProfile() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", AGENT_NAME);
        profile.put("version", "v1.2");
        profile.put("role", "网络安全智能决策体");
        profile.put("status", "ON_DUTY");
        profile.put("slogan", "7×24 小时自主值守，先于攻击发现攻击");
        profile.put("capabilities", Arrays.asList(
                "攻击链关联分析", "自动响应决策", "告警分级研判", "模型自适应重训建议"));
        Map<String, String> decisionTypes = new LinkedHashMap<>();
        decisionTypes.put("ISOLATE", "建议隔离");
        decisionTypes.put("DISCONNECT", "建议断网");
        decisionTypes.put("ALERT_ESCALATE", "告警升级");
        decisionTypes.put("RETRAIN_MODEL", "模型重训");
        profile.put("decisionTypes", decisionTypes);
        return profile;
    }

    // ===== 内部方法 =====

    private AgentAction createAction(Long deviceId, String deviceName, String actionType,
                                      String severity, String reason, String detail,
                                      double confidence) {
        AgentAction action = new AgentAction();
        action.setDeviceId(deviceId);
        action.setDeviceName(deviceName);
        action.setActionTime(LocalDateTime.now());
        action.setActionType(actionType);
        action.setSeverity(severity);
        action.setReason(reason);
        action.setDetail(detail);
        action.setConfidence(Math.round(confidence * 100.0) / 100.0);
        action.setStatus(0);
        return action;
    }

    // ===== 策略与驳回反哺 =====

    /** 该类型决策历史被驳回的次数 */
    private long rejectedCount(String actionType) {
        return agentActionRepository.countByActionTypeAndStatus(actionType, 2);
    }

    /** 某类型当前生效的去重冷却（秒）：基础 5 分钟，每被驳回 1 次 +5 分钟，封顶 30 分钟 */
    private long cooldownSeconds(String actionType) {
        long seconds = BASE_COOLDOWN_SECONDS + rejectedCount(actionType) * COOLDOWN_STEP_SECONDS;
        return Math.min(seconds, COOLDOWN_MAX_SECONDS);
    }

    /** 隔离建议生效门槛：3 次起，该类每被驳回 2 次 +1，封顶 5 次 */
    private long effectiveIsolateThreshold() {
        return Math.min(3 + rejectedCount("ISOLATE") / 2, 5);
    }

    /** 模型重训生效门槛：10 条起，该类每被驳回 1 次 +2 条，封顶 20 条 */
    private long effectiveRetrainThreshold() {
        return Math.min(10 + rejectedCount("RETRAIN_MODEL") * 2, 20);
    }

    /** 按策略开关决定决策是否自动执行 */
    private void applyAutoPolicy(AgentAction action) {
        if (policyService.isAutoExecute(action.getActionType())) {
            action.setStatus(3);
            action.setNote("按自动执行策略放行");
        }
    }

    /** 检查近期是否已生成过同类型决策（去重冷却随驳回次数动态延长） */
    private boolean hasRecentAction(Long deviceId, String actionType) {
        LocalDateTime window = LocalDateTime.now().minusSeconds(cooldownSeconds(actionType));
        if (deviceId != null) {
            return agentActionRepository
                    .findByDeviceIdAndStatusOrderByActionTimeDesc(deviceId, 0)
                    .stream()
                    .anyMatch(a -> actionType.equals(a.getActionType())
                            && a.getActionTime() != null
                            && a.getActionTime().isAfter(window));
        } else {
            return agentActionRepository.findAllByOrderByActionTimeDesc()
                    .stream()
                    .anyMatch(a -> actionType.equals(a.getActionType())
                            && a.getStatus() == 0
                            && a.getActionTime() != null
                            && a.getActionTime().isAfter(window));
        }
    }
}
