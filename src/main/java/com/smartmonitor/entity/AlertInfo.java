package com.smartmonitor.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 入侵告警实体 — AI 检测到异常流量后自动生成告警记录。
 * 包含攻击意图识别结果（攻击类型、置信度、威胁等级）。
 */
@Entity
@Table(name = "alert_info")
public class AlertInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联设备 ID */
    private Long deviceId;

    /** 设备名称 */
    private String deviceName;

    /** 设备 IP */
    private String deviceIp;

    /** 告警时间 */
    private LocalDateTime alertTime;

    /** 攻击类型（DDoS/端口扫描/数据外泄/慢速DoS/蠕虫传播/ARP欺骗/未知异常） */
    private String attackType;

    /** 威胁等级（low/medium/high/critical） */
    private String severity;

    /** AI 异常评分 */
    private Double anomalyScore;

    /** 攻击意图置信度 0~1 */
    private Double confidence;

    /** 入站速率 bps */
    private Long inBps;

    /** 出站速率 bps */
    private Long outBps;

    /** 告警描述 */
    private String description;

    /** 处理状态：0=未处理, 1=已确认, 2=误报, 3=已处理 */
    private Integer status = 0;

    /** 处理备注 */
    private String handleNote;

    /** 处理时间 */
    private LocalDateTime handleTime;

    /** DeepSeek 大模型生成的 AI 研判分析（懒生成并缓存，避免重复调用产生费用） */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String aiAnalysis;

    /** AI 研判生成时间 */
    private LocalDateTime aiAnalysisTime;

    // ===== getters & setters =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long deviceId) { this.deviceId = deviceId; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getDeviceIp() { return deviceIp; }
    public void setDeviceIp(String deviceIp) { this.deviceIp = deviceIp; }

    public LocalDateTime getAlertTime() { return alertTime; }
    public void setAlertTime(LocalDateTime alertTime) { this.alertTime = alertTime; }

    public String getAttackType() { return attackType; }
    public void setAttackType(String attackType) { this.attackType = attackType; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public Double getAnomalyScore() { return anomalyScore; }
    public void setAnomalyScore(Double anomalyScore) { this.anomalyScore = anomalyScore; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public Long getInBps() { return inBps; }
    public void setInBps(Long inBps) { this.inBps = inBps; }

    public Long getOutBps() { return outBps; }
    public void setOutBps(Long outBps) { this.outBps = outBps; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public String getHandleNote() { return handleNote; }
    public void setHandleNote(String handleNote) { this.handleNote = handleNote; }

    public LocalDateTime getHandleTime() { return handleTime; }
    public void setHandleTime(LocalDateTime handleTime) { this.handleTime = handleTime; }

    public String getAiAnalysis() { return aiAnalysis; }
    public void setAiAnalysis(String aiAnalysis) { this.aiAnalysis = aiAnalysis; }

    public LocalDateTime getAiAnalysisTime() { return aiAnalysisTime; }
    public void setAiAnalysisTime(LocalDateTime aiAnalysisTime) { this.aiAnalysisTime = aiAnalysisTime; }
}
