package com.smartmonitor.service;

import com.smartmonitor.entity.Device;

import java.util.List;

public interface DeviceService {

    /** 查询全部设备（纯数据库查询，实时状态由 refreshRealtime 定时刷新） */
    List<Device> listAll();

    /** 查询单个设备（纯数据库查询） */
    Device getById(Long id);

    /** 并发采集所有设备实时状态并持久化（由定时任务调用，保证列表接口秒开） */
    void refreshRealtime();

    /** 新增设备 */
    Device add(Device device);

    /** 修改设备 */
    Device update(Device device);

    /** 删除设备 */
    void delete(Long id);
}
