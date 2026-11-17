package com.smartmonitor.entity;

/**
 * SNMP 实时采集结果。
 */
public class DeviceInfo {

    private String name;
    private String upTime;
    private String status;   // 在线 / 离线

    public DeviceInfo() {
    }

    public DeviceInfo(String name, String upTime, String status) {
        this.name = name;
        this.upTime = upTime;
        this.status = status;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUpTime() {
        return upTime;
    }

    public void setUpTime(String upTime) {
        this.upTime = upTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
