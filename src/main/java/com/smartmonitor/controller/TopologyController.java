package com.smartmonitor.controller;

import com.smartmonitor.entity.Device;
import com.smartmonitor.repository.DeviceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 返回网络拓扑数据（节点 + 链路），供前端 ECharts Graph/Tree 渲染。
 * 节点与 device 表真实纳管设备一一对应（id 与 WebSocket 推送的 deviceId 一致），
 * 设备增删后前端拓扑会自动同步。
 */
@RestController
@RequestMapping("/api/topology")
public class TopologyController {

    private final DeviceRepository deviceRepository;

    public TopologyController(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @GetMapping
    public Map<String, Object> getTopology() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Device d : deviceRepository.findAll()) {
            String type = d.getType() != null ? d.getType() : "";
            String t = type.toLowerCase();
            int category;
            String symbol;
            if (t.contains("路由") || t.contains("router")) {
                category = 0; symbol = "router";
            } else if (t.contains("防火墙") || t.contains("fw")) {
                category = 0; symbol = "router";
            } else if (t.contains("交换") || t.contains("switch")) {
                category = 2; symbol = "switch";
            } else if (t.contains("服务") || t.contains("server")) {
                category = 3; symbol = "server";
            } else if (t.contains("ap") || t.contains("无线")) {
                category = 3; symbol = "ap";
            } else {
                category = 3; symbol = "pc";
            }
            String name = firstNonEmpty(d.getName(), d.getRemark(), "设备-" + d.getId());
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", d.getId());               // 与 device.id / WebSocket deviceId 一致
            n.put("name", name);
            n.put("deviceType", type);
            n.put("ip", d.getIp());
            n.put("category", category);
            n.put("symbol", symbol);
            nodes.add(n);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        // 链路信息暂无法从 SNMP 采集获得，先返回空链路，前端树形布局会自动挂虚拟根
        result.put("links", new ArrayList<>());
        return result;
    }

    private String firstNonEmpty(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) return c.trim();
        }
        return "";
    }
}
