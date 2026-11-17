package com.smartmonitor.controller;

import com.smartmonitor.service.DiscoveryService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 网络自动发现 REST API。
 *
 * 接口：
 *   GET  /api/discovery/segment  — 自动检测网段
 *   POST /api/discovery/scan     — 启动扫描（异步，进度通过 WebSocket 推送）
 *   GET  /api/discovery/status    — 查询扫描状态
 *   POST /api/discovery/add       — 将发现的设备批量加入管理
 */
@RestController
@RequestMapping("/api/discovery")
@CrossOrigin(originPatterns = "*")
public class DiscoveryController {

    private final DiscoveryService discoveryService;

    public DiscoveryController(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    /** 自动检测本机所在网段 */
    @GetMapping("/segment")
    public Map<String, Object> detectSegment() {
        String segment = discoveryService.detectNetworkSegment();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("segment", segment);
        return result;
    }

    /** 启动网络扫描（异步执行，进度通过 WebSocket 推送） */
    @PostMapping("/scan")
    public Map<String, Object> startScan(@RequestBody(required = false) Map<String, String> body) {
        String segment = body != null ? body.get("segment") : null;
        String community = body != null ? body.get("community") : null;
        if (segment == null || segment.isEmpty()) {
            segment = discoveryService.detectNetworkSegment();
        }
        if (community == null || community.isEmpty()) {
            community = discoveryService.getDefaultCommunity();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (discoveryService.isScanning()) {
            result.put("success", false);
            result.put("message", "扫描正在进行中，请等待完成");
            return result;
        }

        discoveryService.startDiscovery(segment, community);
        result.put("success", true);
        result.put("message", "扫描已启动，请通过 WebSocket 关注进度");
        result.put("segment", segment);
        result.put("community", community);
        return result;
    }

    /** 查询当前扫描状态 */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanning", discoveryService.isScanning());
        return result;
    }

    /**
     * 获取最近一次扫描结果（含进度、设备列表、扫描状态）。
     * 前端进入页面 / 刷新页面时调用，避免错过 WebSocket 实时推送。
     */
    @GetMapping("/result")
    public Map<String, Object> getResult() {
        return discoveryService.getLastResult();
    }

    /** 将发现的设备批量加入 device 表 */
    @PostMapping("/add")
    public Map<String, Object> addDevices(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> ips = (List<String>) body.get("ips");
        String community = (String) body.get("community");
        if (community == null || community.isEmpty()) {
            community = discoveryService.getDefaultCommunity();
        }

        if (ips == null || ips.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "IP 列表为空");
            return result;
        }

        int added = discoveryService.addDiscoveredDevices(ips, community);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("added", added);
        result.put("message", "成功加入 " + added + " 台设备");
        return result;
    }
}
