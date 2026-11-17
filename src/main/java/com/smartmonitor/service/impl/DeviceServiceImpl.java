package com.smartmonitor.service.impl;

import com.smartmonitor.entity.Device;
import com.smartmonitor.entity.DeviceInfo;
import com.smartmonitor.repository.DeviceRepository;
import com.smartmonitor.service.DeviceService;
import com.smartmonitor.service.SnmpService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class DeviceServiceImpl implements DeviceService {

    private final DeviceRepository deviceRepository;
    private final SnmpService snmpService;

    /** 设备信息并发采集线程池（IO 密集，线程数与设备数解耦容器 CPU 限制） */
    private final ExecutorService snmpExecutor = Executors.newFixedThreadPool(16);

    public DeviceServiceImpl(DeviceRepository deviceRepository, SnmpService snmpService) {
        this.deviceRepository = deviceRepository;
        this.snmpService = snmpService;
    }

    @Override
    public List<Device> listAll() {
        // 纯数据库查询：实时状态由 refreshRealtime() 后台定时刷新后落库，
        // 避免每次列表请求同步做 SNMP 探测（离线设备会拖慢到数秒甚至十几秒）。
        return deviceRepository.findAll();
    }

    @Override
    public Device getById(Long id) {
        return deviceRepository.findById(id).orElse(null);
    }

    @Override
    public void refreshRealtime() {
        // 并发采集各设备实时信息并落库（每台独立 Snmp 实例，线程安全）
        List<Device> devices = deviceRepository.findAll();
        List<Future<?>> futures = devices.stream()
                .map(d -> snmpExecutor.submit(() -> {
                    try {
                        deviceRepository.save(fillRealtime(d));
                    } catch (Exception ignored) {
                        // 单设备采集/保存失败不影响其他设备
                    }
                }))
                .collect(Collectors.toList());
        for (Future<?> f : futures) {
            try {
                f.get(15, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 单设备超时不阻塞整体刷新
            }
        }
    }

    @Override
    public Device add(Device device) {
        if (device.getCommunity() == null || device.getCommunity().isEmpty()) {
            device.setCommunity("public");
        }
        return deviceRepository.save(device);
    }

    @Override
    public Device update(Device device) {
        return deviceRepository.save(device);
    }

    @Override
    public void delete(Long id) {
        deviceRepository.deleteById(id);
    }

    /** 用 SNMP 实时采集结果填充设备的 name/status/upTime */
    private Device fillRealtime(Device device) {
        DeviceInfo info = snmpService.collectDeviceInfo(device.getIp(), device.getCommunity());
        device.setName(info.getName());
        device.setStatus(info.getStatus());
        device.setUpTime(info.getUpTime());
        return device;
    }
}
