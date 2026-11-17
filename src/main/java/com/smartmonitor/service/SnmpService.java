package com.smartmonitor.service;

import com.smartmonitor.entity.DeviceInfo;

public interface SnmpService {

    /**
     * 向指定设备发起 SNMP 采集，返回设备名 / 运行时间 / 在线状态
     *
     * @param ip        目标设备 IP
     * @param community SNMP 社区字符串
     */
    DeviceInfo collectDeviceInfo(String ip, String community);

    /**
     * 通过 SNMP 采集接口流量计数器。
     * GET ifInOctets (1.3.6.1.2.1.2.2.1.10.1) 和 ifOutOctets (1.3.6.1.2.1.2.2.1.16.1)
     *
     * @param ip        目标设备 IP
     * @param community SNMP 社区字符串
     * @return long[]{inOctets, outOctets}，设备不可达时返回 null
     */
    long[] collectTraffic(String ip, String community);

    /**
     * 快速探测目标 IP 是否存在 SNMP 设备（GET sysName + sysDescr，超时短）。
     * 用于网络自动发现。
     *
     * @param ip        目标设备 IP
     * @param community SNMP 社区字符串
     * @param timeoutMs 探测超时（毫秒）
     * @return [sysName, sysDescr]，不可达返回 null
     */
    String[] probeDevice(String ip, String community, int timeoutMs);
}
