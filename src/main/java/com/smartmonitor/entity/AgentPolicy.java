package com.smartmonitor.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 智能体「小七」自动执行策略 — 每种决策类型一条配置。
 * autoExecute=true 时，该类型决策生成后无需人工确认自动执行（status=3）。
 */
@Entity
@Table(name = "agent_policy")
public class AgentPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 决策类型（ISOLATE/DISCONNECT/ADJUST_THRESHOLD/ALERT_ESCALATE/RETRAIN_MODEL），唯一 */
    @Column(unique = true, nullable = false, length = 32)
    private String actionType;

    /** 是否自动执行 */
    @Column(nullable = false)
    private Boolean autoExecute = false;

    /** 最后更新时间 */
    private LocalDateTime updatedTime;

    /** 最后更新人（用户名） */
    @Column(length = 64)
    private String updatedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public Boolean getAutoExecute() { return autoExecute; }
    public void setAutoExecute(Boolean autoExecute) { this.autoExecute = autoExecute; }

    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
