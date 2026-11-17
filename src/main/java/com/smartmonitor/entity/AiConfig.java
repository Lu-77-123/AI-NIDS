package com.smartmonitor.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * AI 检测引擎运行期配置（单行表，首次启动自动写入默认值）
 * 保存后立即生效，无需重启后端。
 */
@Entity
@Table(name = "ai_config")
public class AiConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 稳健 z 分数阈值：向上突增超过该倍数才判异常 */
    @Column(nullable = false)
    private double zThreshold = 2.0;

    /** 绝对流量下限（bps）：低于该值的突增不告警，防基线近零时误报 */
    @Column(nullable = false)
    private long minAnomalyBps = 50000;

    /** 基线滑动窗口样本数 */
    @Column(nullable = false)
    private int windowSize = 30;

    /** 最少样本数：历史不足该数量不判定 */
    @Column(nullable = false)
    private int minSamples = 5;

    /** 同设备同攻击类型告警冷却分钟数 */
    @Column(nullable = false)
    private long cooldownMinutes = 5;

    /** DeepSeek 模型名覆盖（空则用 application.yml 配置） */
    @Column(length = 64)
    private String deepseekModel;

    @Column(length = 32)
    private String updatedBy;

    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public double getZThreshold() { return zThreshold; }
    public void setZThreshold(double zThreshold) { this.zThreshold = zThreshold; }
    public long getMinAnomalyBps() { return minAnomalyBps; }
    public void setMinAnomalyBps(long minAnomalyBps) { this.minAnomalyBps = minAnomalyBps; }
    public int getWindowSize() { return windowSize; }
    public void setWindowSize(int windowSize) { this.windowSize = windowSize; }
    public int getMinSamples() { return minSamples; }
    public void setMinSamples(int minSamples) { this.minSamples = minSamples; }
    public long getCooldownMinutes() { return cooldownMinutes; }
    public void setCooldownMinutes(long cooldownMinutes) { this.cooldownMinutes = cooldownMinutes; }
    public String getDeepseekModel() { return deepseekModel; }
    public void setDeepseekModel(String deepseekModel) { this.deepseekModel = deepseekModel; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
