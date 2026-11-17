package com.smartmonitor.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 攻击意图识别器 — 根据流量统计特征模式推断攻击类型。
 * 基于 SNMP 可获取的统计特征（无 DPI），通过规则引擎匹配 6 种典型攻击模式。
 *
 * 速率阈值全部通过 snmp.detection.* 配置，生产环境默认按园区网链路标定（500/200/100Mbps）；
 * eNSP 等实验环境可在 application.yml 中下调到 Mbps 以下，使真实实验流量即可触发完整决策链。
 */
@Component
public class AttackIntentClassifier {

    /** DDoS：入站速率阈值（Mbps，默认 500） */
    @Value("${snmp.detection.ddos-mbps:500}")
    private double ddosMbps;

    /** DDoS：入/出流量比阈值（默认 10） */
    @Value("${snmp.detection.ddos-ratio:10}")
    private double ddosRatio;

    /** 数据外泄：出站速率阈值（Mbps，默认 200） */
    @Value("${snmp.detection.exfil-mbps:200}")
    private double exfilMbps;

    /** 数据外泄：出/入比判定（in/out < 该值，默认 0.2） */
    @Value("${snmp.detection.exfil-ratio:0.2}")
    private double exfilRatio;

    /** 蠕虫传播：双向速率阈值（Mbps，默认 100） */
    @Value("${snmp.detection.worm-mbps:100}")
    private double wormMbps;

    /**
     * 根据流量特征推断攻击意图
     * @param inBps       入站速率 bps
     * @param outBps      出站速率 bps
     * @param anomalyScore AI 异常评分（越负越异常）
     * @return Map: attackType, severity, confidence, description
     */
    public Map<String, Object> classify(long inBps, long outBps, double anomalyScore) {
        Map<String, Object> result = new HashMap<>();

        double inMbps = inBps / 1_000_000.0;
        double outMbps = outBps / 1_000_000.0;
        double ratio = (outBps > 0) ? (double) inBps / outBps : 999;

        // ---- 规则1: DDoS 入站突发 ----
        // 特征: 入站 >> 出站, 入站超阈值, 比值超阈值
        if (inMbps > ddosMbps && ratio > ddosRatio) {
            result.put("attackType", "DDoS");
            result.put("severity", "critical");
            result.put("confidence", Math.min(0.98, 0.7 + Math.abs(anomalyScore)));
            result.put("description", String.format(
                "检测到入站流量突发 %.2f Mbps（阈值 %.2f），入/出比 %.1f，疑似 DDoS 拒绝服务攻击", inMbps, ddosMbps, ratio));
            return result;
        }

        // ---- 规则2: 数据外泄 出站突发 ----
        // 特征: 出站 >> 入站, 出站超阈值, 出/入超阈值
        if (outMbps > exfilMbps && ratio < exfilRatio) {
            result.put("attackType", "数据外泄");
            result.put("severity", "high");
            result.put("confidence", Math.min(0.95, 0.65 + Math.abs(anomalyScore)));
            result.put("description", String.format(
                "检测到出站流量异常 %.2f Mbps（阈值 %.2f），出/入比 %.1f，疑似数据外泄", outMbps, exfilMbps, 1.0/ratio));
            return result;
        }

        // ---- 规则5: 蠕虫传播 (提前判断，优先于端口扫描和慢速DoS) ----
        // 特征: 双向高流量且异常评分低, 入出接近
        if (anomalyScore < -0.03 && inMbps > wormMbps && outMbps > wormMbps) {
            result.put("attackType", "蠕虫传播");
            result.put("severity", "high");
            result.put("confidence", Math.min(0.90, 0.6 + Math.abs(anomalyScore)));
            result.put("description", String.format(
                "双向异常流量（入 %.2f / 出 %.2f Mbps，阈值 %.2f），评分 %.4f，疑似蠕虫病毒横向传播", inMbps, outMbps, wormMbps, anomalyScore));
            return result;
        }

        // ---- 规则3: 端口扫描 ----
        // 特征: 流量不高但异常评分低, 双向对称抖动
        if (anomalyScore < -0.3 && inMbps < 50 && outMbps < 50 && ratio > 0.5 && ratio < 2.0) {
            result.put("attackType", "端口扫描");
            result.put("severity", "medium");
            result.put("confidence", Math.min(0.85, 0.5 + Math.abs(anomalyScore)));
            result.put("description", "流量速率不高但 AI 评分异常，双向对称抖动，疑似端口扫描探测");
            return result;
        }

        // ---- 规则4: 慢速 DoS ----
        // 特征: 持续异常但流量不高
        if (anomalyScore < -0.2 && inMbps < 20 && outMbps < 20) {
            result.put("attackType", "慢速DoS");
            result.put("severity", "medium");
            result.put("confidence", Math.min(0.80, 0.45 + Math.abs(anomalyScore)));
            result.put("description", "低速持续异常流量，疑似慢速 DoS 攻击（Slowloris）");
            return result;
        }

        // ---- 默认: 未知异常 ----
        result.put("attackType", "未知异常");
        result.put("severity", anomalyScore < -0.5 ? "high" : "low");
        result.put("confidence", Math.min(0.70, 0.3 + Math.abs(anomalyScore)));
        result.put("description", String.format(
            "AI 检测到异常流量，评分 %.4f，入 %.1f Mbps / 出 %.1f Mbps", anomalyScore, inMbps, outMbps));
        return result;
    }
}
