package com.smartmonitor.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 流量数据实体，由 SNMP 定时采集 ifInOctets / ifOutOctets 并计算速率后存入。
 */
@Entity
@Table(name = "traffic_data")
public class TrafficData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long deviceId;
    private String deviceIp;
    private String deviceName;

    /** SNMP 采集的累计入字节数 (ifInOctets) */
    private Long inOctets;

    /** SNMP 采集的累计出字节数 (ifOutOctets) */
    private Long outOctets;

    /** 入方向速率 (bps) — 由两次采集差值计算 */
    private Long inBps;

    /** 出方向速率 (bps) — 由两次采集差值计算 */
    private Long outBps;

    /** 是否异常：0=正常，1=异常（滑动窗口 μ±2σ 检测） */
    private Integer isAnomaly;

    /** 异常评分（-0.5~0.5，越小越异常；由中位数+MAD 稳健统计基线计算） */
    private Double anomalyScore;

    /** 采集时间 */
    private LocalDateTime collectTime;

    public TrafficData() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(Long deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceIp() {
        return deviceIp;
    }

    public void setDeviceIp(String deviceIp) {
        this.deviceIp = deviceIp;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public Long getInOctets() {
        return inOctets;
    }

    public void setInOctets(Long inOctets) {
        this.inOctets = inOctets;
    }

    public Long getOutOctets() {
        return outOctets;
    }

    public void setOutOctets(Long outOctets) {
        this.outOctets = outOctets;
    }

    public Long getInBps() {
        return inBps;
    }

    public void setInBps(Long inBps) {
        this.inBps = inBps;
    }

    public Long getOutBps() {
        return outBps;
    }

    public void setOutBps(Long outBps) {
        this.outBps = outBps;
    }

    public Integer getIsAnomaly() {
        return isAnomaly;
    }

    public void setIsAnomaly(Integer isAnomaly) {
        this.isAnomaly = isAnomaly;
    }

    public Double getAnomalyScore() {
        return anomalyScore;
    }

    public void setAnomalyScore(Double anomalyScore) {
        this.anomalyScore = anomalyScore;
    }

    public LocalDateTime getCollectTime() {
        return collectTime;
    }

    public void setCollectTime(LocalDateTime collectTime) {
        this.collectTime = collectTime;
    }
}
