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
        List<Device> devices = deviceRepository.findAll();
        for (Device d : devices) {
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
        // 物理链路无法从 SNMP 直接获取，这里按"路由 → 交换 → 终端"的类型层级生成逻辑连线，
        // 前端 tree 布局可直接渲染；链路性质在 linkType 中标注为"逻辑链路"，避免与真实拓扑混淆。
        result.put("links", buildLogicalLinks(devices));
        return result;
    }

    /** 设备层级：0=路由/防火墙（出口），1=交换机（汇聚），2=服务器/终端 */
    private int levelOf(Device d) {
        String type = d.getType() != null ? d.getType().toLowerCase() : "";
        if (type.contains("路由") || type.contains("router") || type.contains("防火墙") || type.contains("fw")) {
            return 0;
        }
        if (type.contains("交换") || type.contains("switch")) {
            return 1;
        }
        return 2;
    }

    /**
     * 生成逻辑层级连线：交换机挂到出口设备，终端挂到同网段交换机（无则挂出口），
     * 保证每个非根节点只有一条入边，前端 tree 布局无需再修正。
     */
    private List<Map<String, Object>> buildLogicalLinks(List<Device> devices) {
        List<Map<String, Object>> links = new ArrayList<>();
        if (devices == null || devices.isEmpty()) return links;

        Device gateway = null;
        List<Device> switches = new ArrayList<>();
        List<Device> leaves = new ArrayList<>();
        for (Device d : devices) {
            int lv = levelOf(d);
            if (lv == 0 && gateway == null) {
                gateway = d;
            } else if (lv == 1) {
                switches.add(d);
            } else if (lv == 2) {
                leaves.add(d);
            } else if (lv == 0) {
                // 多余的路由设备也作为子节点挂到首个出口设备下
                leaves.add(d);
            }
        }

        // 无路由设备时，首个交换机充当根，其余交换机挂到它下面
        Device switchRoot = switches.isEmpty() ? null : switches.get(0);
        if (gateway == null && switchRoot != null) {
            for (int i = 1; i < switches.size(); i++) {
                links.add(buildLink(switchRoot, switches.get(i)));
            }
        } else if (gateway != null) {
            for (Device s : switches) {
                if (!sameId(s, gateway)) links.add(buildLink(gateway, s));
            }
            for (Device leaf : leaves) {
                if (levelOf(leaf) == 0 && !sameId(leaf, gateway)) links.add(buildLink(gateway, leaf));
            }
        }

        for (Device leaf : leaves) {
            if (levelOf(leaf) == 0) continue;
            Device parent = pickSwitch(leaf, switches);
            if (parent == null) parent = gateway;
            if (parent != null && !sameId(parent, leaf)) {
                links.add(buildLink(parent, leaf));
            }
        }
        return links;
    }

    /** 优先选同 /24 网段的交换机，否则退回第一台交换机 */
    private Device pickSwitch(Device leaf, List<Device> switches) {
        if (switches == null || switches.isEmpty()) return null;
        String prefix = ipPrefix(leaf.getIp());
        if (prefix != null) {
            for (Device s : switches) {
                if (prefix.equals(ipPrefix(s.getIp()))) return s;
            }
        }
        return switches.get(0);
    }

    private static String ipPrefix(String ip) {
        if (ip == null || !ip.contains(".")) return null;
        int idx = ip.lastIndexOf('.');
        return idx > 0 ? ip.substring(0, idx) : null;
    }

    private static boolean sameId(Device a, Device b) {
        return a != null && b != null && a.getId() != null && a.getId().equals(b.getId());
    }

    private Map<String, Object> buildLink(Device parent, Device child) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("source", parent.getId());
        link.put("target", child.getId());
        link.put("linkType", "逻辑链路（按设备类型推导）");
        return link;
    }

    private String firstNonEmpty(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) return c.trim();
        }
        return "";
    }
}
