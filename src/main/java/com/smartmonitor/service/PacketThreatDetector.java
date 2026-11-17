package com.smartmonitor.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 包级攻击特征检测器（旁路抓包）。
 *
 * 输入为 1 秒窗口内的报文统计，输出攻击类型/等级/置信度/描述；无威胁返回 null。
 * 与基于 SNMP 统计的 AttackIntentClassifier 是两条独立通道：
 *   - SNMP 统计：宏观、无需权限，但看不到包级特征
 *   - 本类（Pcap）：包级，能识别 SYN 占比、端口探测数等精细特征
 */
@Component
public class PacketThreatDetector {

    /** SYN Flood：SYN 速率阈值（pps） */
    @Value("${pcap.syn-pps:200}")
    private int synPpsThreshold;

    /** SYN Flood：SYN 占 TCP 报文比例阈值（0~1） */
    @Value("${pcap.syn-ratio:0.7}")
    private double synRatio;

    /** UDP Flood：UDP 速率阈值（pps） */
    @Value("${pcap.udp-pps:500}")
    private int udpPpsThreshold;

    /** 端口扫描：窗口内不同目的端口数阈值 */
    @Value("${pcap.scan-ports:20}")
    private int scanPortThreshold;

    /**
     * 按 1 秒窗口的包统计判断 SYN Flood / UDP Flood。
     *
     * @return 威胁描述；无威胁返回 null
     */
    public Map<String, Object> detect(double secs, long total, long tcp, long syn, long udp) {
        if (secs <= 0 || total < 10) {
            return null; // 样本太少，不做判断，避免噪声误报
        }
        double synPps = syn / secs;
        double udpPps = udp / secs;
        double totalPps = total / secs;

        // ---- SYN Flood：SYN 速率高，且 SYN 在 TCP 中占比高（大量半开连接特征）----
        if (tcp > 0 && synPps >= synPpsThreshold && (syn / (double) tcp) >= synRatio) {
            return threat("SYN Flood", "critical", clamp(0.70 + synPps / 3000.0),
                    String.format("SYN 速率 %.0f pps（阈值 %d），SYN 占 TCP %.0f%%（阈值 %.0f%%），疑似 SYN Flood",
                            synPps, synPpsThreshold, syn * 100.0 / tcp, synRatio * 100),
                    totalPps, synPps, udpPps);
        }

        // ---- UDP Flood：UDP 速率高（无连接洪泛）----
        if (udpPps >= udpPpsThreshold) {
            return threat("UDP Flood", "high", clamp(0.65 + udpPps / 6000.0),
                    String.format("UDP 速率 %.0f pps（阈值 %d），疑似 UDP Flood", udpPps, udpPpsThreshold),
                    totalPps, synPps, udpPps);
        }

        return null;
    }

    /**
     * 端口扫描：同一源 IP 在短时间内探测大量不同目的端口。
     */
    public Map<String, Object> detectPortScan(String srcIp, int distinctPorts, long secs) {
        if (distinctPorts >= scanPortThreshold && secs <= 20) {
            return threat("端口扫描", "medium", clamp(0.50 + distinctPorts / 120.0),
                    String.format("源 %s 在 %d 秒内探测了 %d 个不同端口（阈值 %d），疑似端口扫描",
                            srcIp, secs, distinctPorts, scanPortThreshold),
                    0, 0, 0);
        }
        return null;
    }

    private Map<String, Object> threat(String type, String severity, double confidence,
                                       String description, double totalPps, double synPps, double udpPps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attackType", type);
        m.put("severity", severity);
        m.put("confidence", Math.round(confidence * 100) / 100.0);
        m.put("description", description);
        m.put("source", "pcap");
        m.put("totalPps", Math.round(totalPps));
        m.put("synPps", Math.round(synPps));
        m.put("udpPps", Math.round(udpPps));
        return m;
    }

    private static double clamp(double v) {
        return Math.max(0.05, Math.min(0.99, v));
    }
}
