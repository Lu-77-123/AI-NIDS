package com.smartmonitor.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 智能体决策记录 — 检测到持续攻击后，智能体自动生成响应决策。
 * 管理员可执行或驳回决策。
 */
@Entity
@Table(name = "agent_action")
public class AgentAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联设备 ID */
    private Long deviceId;

    /** 设备名称 */
    private String deviceName;

    /** 决策时间 */
    private LocalDateTime actionTime;

    /**
     * 决策类型：
     * ISOLATE       — 建议隔离设备
     * DISCONNECT    — 建议断网
     * ADJUST_THRESHOLD — 自适应调整阈值
     * ALERT_ESCALATE — 告警升级
     * RETRAIN_MODEL — 触发模型重训
     */
    private String actionType;

    /** 决策原因 */
    private String reason;

    /** 决策详情（JSON 格式的上下文信息） */
    private String detail;

    /** 严重程度（low/medium/high/critical） */
    private String severity;

    /** 决策置信度（0.00~1.00），由智能体「小七」根据证据强度评估 */
    private Double confidence;

    /**
     * 执行状态：0=待处理, 1=已执行, 2=已驳回, 3=自动执行
     */
    private Integer status = 0;

    /** 执行/驳回时间 */
    private LocalDateTime executeTime;

    /** 管理员备注 */
    private String note;

    // ===== getters & setters =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDeviceId() { return deviceId; }
    public void setDeviceId(Long deviceId) { this.deviceId = deviceId; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public LocalDateTime getActionTime() { return actionTime; }
    public void setActionTime(LocalDateTime actionTime) { this.actionTime = actionTime; }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getExecuteTime() { return executeTime; }
    public void setExecuteTime(LocalDateTime executeTime) { this.executeTime = executeTime; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
