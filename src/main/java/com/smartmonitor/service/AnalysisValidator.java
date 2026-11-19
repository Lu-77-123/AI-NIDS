package com.smartmonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartmonitor.entity.AlertInfo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 研判结果校验器。
 *
 * 大模型的自由文本输出存在两类问题：格式不可解析、结论与自身字段/客观数据矛盾
 * （例如置信度写 90% 却在正文里说"更像误报"）。本类要求模型输出固定 JSON 结构，
 * 解析后做字段与一致性双重校验；校验不通过的输出不允许直接入库展示。
 */
@Component
public class AnalysisValidator {

    public static final String LEVEL_REAL = "real";
    public static final String LEVEL_SUSPICIOUS = "suspicious";
    public static final String LEVEL_FALSE_POSITIVE = "false_positive";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 解析并校验一次模型输出。
     *
     * @param raw 模型返回的原始文本（允许带 ```json 围栏）
     * @param alert 告警原文
     * @param ev 取证结果
     * @return 校验结果；valid=false 时 errors 中给出具体原因，调用方可据此重试或降级
     */
    public Result validate(String raw, AlertInfo alert, AlertEvidenceCollector.Evidence ev) {
        Result r = new Result();
        if (raw == null || raw.trim().isEmpty()) {
            r.errors.add("模型返回为空");
            return r;
        }

        JsonNode root;
        try {
            root = mapper.readTree(stripFence(raw));
        } catch (Exception e) {
            r.errors.add("输出不是合法 JSON：" + e.getMessage());
            return r;
        }
        if (root == null || !root.isObject()) {
            r.errors.add("输出不是 JSON 对象");
            return r;
        }

        // ---- 字段校验 ----
        r.level = text(root, "threat_level");
        if (r.level == null || !(LEVEL_REAL.equals(r.level) || LEVEL_SUSPICIOUS.equals(r.level)
                || LEVEL_FALSE_POSITIVE.equals(r.level))) {
            r.errors.add("threat_level 必须是 real / suspicious / false_positive 之一，实际为：" + r.level);
        }

        JsonNode confNode = root.get("confidence");
        if (confNode == null || !confNode.isNumber()) {
            r.errors.add("confidence 缺失或不是数字");
        } else {
            r.confidence = confNode.asDouble();
            if (r.confidence < 0 || r.confidence > 1) {
                r.errors.add("confidence 超出 [0,1] 范围：" + r.confidence);
            }
        }

        r.reasoning = text(root, "reasoning");
        r.evidence = array(root, "evidence");
        r.causes = array(root, "causes");
        r.actions = array(root, "actions");

        if (r.evidence.isEmpty()) {
            r.errors.add("evidence 为空，未引用任何取证事实");
        }
        if (r.evidence.size() > 6) {
            r.evidence = new ArrayList<>(r.evidence.subList(0, 6));
        }
        if (r.actions.isEmpty()) {
            r.errors.add("actions 为空，未给出可执行建议");
        }
        if (r.reasoning == null || r.reasoning.trim().isEmpty()) {
            // 缺判断依据属于质量问题，但不阻断展示
            r.errors.add("reasoning 为空");
        }

        // ---- 一致性校验：结论必须与自己给出的置信度、系统评分、取证事实相容 ----
        if (LEVEL_REAL.equals(r.level) && r.confidence > 0 && r.confidence < 0.5) {
            r.errors.add("自相矛盾：判定为真实威胁，但置信度仅 " + percent(r.confidence));
        }
        if (LEVEL_FALSE_POSITIVE.equals(r.level) && r.confidence > 0 && r.confidence < 0.5) {
            r.errors.add("自相矛盾：判定为误报，但置信度仅 " + percent(r.confidence));
        }
        if (LEVEL_REAL.equals(r.level) && alert.getAnomalyScore() != null && alert.getAnomalyScore() >= 0) {
            r.errors.add("自相矛盾：系统异常评分为非负（未判异常），却给出真实威胁结论");
        }
        if (LEVEL_REAL.equals(r.level) && ev != null && ev.sampleCount > 0
                && !ev.highestInWindow && ev.currentVsMedian > 0 && ev.currentVsMedian < 1.5) {
            r.errors.add("与取证不符：当前流量未超过基线 1.5 倍，不足以支撑真实威胁结论");
        }

        r.valid = r.errors.isEmpty();
        if (r.valid) {
            r.rendered = render(r, alert, ev);
        }
        return r;
    }

    /** 校验失败时的降级摘要：只用客观证据说话，并明确标注未经模型确认 */
    public String renderFallback(AlertInfo alert, AlertEvidenceCollector.Evidence ev, List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("【威胁判断】大模型输出未通过一致性校验，暂不下结论。");
        if (alert.getAnomalyScore() != null) {
            sb.append("系统异常评分 ").append(String.format("%.2f", alert.getAnomalyScore())).append("。");
        }
        sb.append('\n');
        if (ev != null) {
            sb.append("【客观证据】当前入向 ").append(ev.currentInBps).append(" bps，窗口基线 ")
                    .append(ev.medianInBps).append(" bps");
            if (ev.medianInBps > 0) {
                sb.append("，为基线 ").append(String.format("%.1f", ev.currentVsMedian)).append(" 倍");
            }
            if (ev.consecutiveAbove >= 2) {
                sb.append("，已持续 ").append(ev.consecutiveAbove).append(" 轮");
            }
            sb.append("；同设备历史告警 ").append(ev.alertCountSameDevice).append(" 次。\n");
        }
        sb.append("【处置建议】1. 人工复核该设备当前会话与流量构成；2. 确认是否为计划内业务变更；");
        sb.append("3. 复核后可将该告警标记为误报或升级。");
        if (errors != null && !errors.isEmpty()) {
            sb.append("\n（校验未通过原因：").append(String.join("；", errors)).append("）");
        }
        return sb.toString();
    }

    private String render(Result r, AlertInfo alert, AlertEvidenceCollector.Evidence ev) {
        StringBuilder sb = new StringBuilder();
        sb.append("【威胁判断】").append(levelLabel(r.level))
                .append("（模型置信度 ").append(percent(r.confidence)).append("）");
        if (r.reasoning != null && !r.reasoning.trim().isEmpty()) {
            sb.append("。依据：").append(r.reasoning.trim());
        }
        sb.append('\n');

        sb.append("【可能成因】");
        appendList(sb, r.causes);
        sb.append('\n');

        sb.append("【处置建议】");
        appendList(sb, r.actions);
        sb.append('\n');

        sb.append("【已核对证据】");
        appendList(sb, r.evidence);
        if (ev != null && ev.sampleCount > 0) {
            sb.append("\n（样本 ").append(ev.sampleCount).append(" 轮，基线 ")
                    .append(ev.medianInBps).append(" bps，当前 ")
                    .append(ev.currentInBps).append(" bps）");
        }
        return sb.toString();
    }

    private void appendList(StringBuilder sb, List<String> items) {
        if (items == null || items.isEmpty()) {
            sb.append("无");
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(' ');
            // 模型常自带 "1. " 序号，渲染时统一重新编号，避免出现 "1. 1. xxx"
            String item = items.get(i).trim().replaceFirst("^\\d+\\s*[.、]\\s*", "");
            sb.append(i + 1).append(". ").append(item);
        }
    }

    private static String levelLabel(String level) {
        if (LEVEL_REAL.equals(level)) return "真实威胁";
        if (LEVEL_FALSE_POSITIVE.equals(level)) return "可能误报";
        return "疑似威胁";
    }

    private static String percent(double v) {
        return String.format("%.0f%%", v * 100);
    }

    private static String stripFence(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstLineEnd = s.indexOf('\n');
            if (firstLineEnd > 0) s = s.substring(firstLineEnd + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        }
        return s.trim();
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static List<String> array(JsonNode root, String field) {
        List<String> list = new ArrayList<>();
        JsonNode n = root.get(field);
        if (n != null && n.isArray()) {
            for (JsonNode item : n) {
                String s = item.asText();
                if (s != null && !s.trim().isEmpty()) list.add(s.trim());
            }
        }
        return list;
    }

    /** 校验结果 */
    public static class Result {
        public boolean valid;
        public String level;
        public double confidence;
        public String reasoning;
        public List<String> evidence = new ArrayList<>();
        public List<String> causes = new ArrayList<>();
        public List<String> actions = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public String rendered;
    }
}
