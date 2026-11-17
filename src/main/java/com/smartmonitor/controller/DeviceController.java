package com.smartmonitor.controller;

import com.smartmonitor.entity.Device;
import com.smartmonitor.service.DeviceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/device")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /** 查询全部设备（含 SNMP 实时采集） */
    @GetMapping
    public List<Device> list() {
        return deviceService.listAll();
    }

    /** 查询单个设备 */
    @GetMapping("/{id}")
    public Device get(@PathVariable Long id) {
        return deviceService.getById(id);
    }

    /** 新增设备 */
    @PostMapping
    public Device add(@RequestBody Device device) {
        return deviceService.add(device);
    }

    /** 修改设备 */
    @PutMapping
    public Device update(@RequestBody Device device) {
        return deviceService.update(device);
    }

    /** 删除设备 */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        deviceService.delete(id);
    }
}
