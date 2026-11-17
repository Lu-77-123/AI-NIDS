package com.smartmonitor.controller;

import com.smartmonitor.entity.TrafficData;
import com.smartmonitor.repository.DeviceRepository;
import com.smartmonitor.repository.TrafficDataRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/traffic")
public class TrafficController {

    /** 最近一次采集在该时间窗内才算在线（采集周期 10s，容忍 3 个周期丢包） */
    private static final long ONLINE_WINDOW_SECONDS = 30;

    private final TrafficDataRepository trafficDataRepository;
    private final DeviceRepository deviceRepository;

    public TrafficController(TrafficDataRepository trafficDataRepository,
                             DeviceRepository deviceRepository) {
        this.trafficDataRepository = trafficDataRepository;
        this.deviceRepository = deviceRepository;
    }

    /** 获取所有设备最新流量数据 */
    @GetMapping("/latest")
    public List<TrafficData> getLatest() {
        return trafficDataRepository.findLatestForAllDevices();
    }

    /** 获取某设备的历史流量趋势（最近30条，按时间升序） */
    @GetMapping("/history/{deviceId}")
    public List<TrafficData> getHistory(@PathVariable Long deviceId) {
        return trafficDataRepository.findRecent30ByDeviceIdOrderByCollectTimeAsc(deviceId);
    }

    /** 获取流量概览统计 */
    @GetMapping("/overview")
    public Map<String, Object> getOverview() {
        List<TrafficData> latest = trafficDataRepository.findLatestForAllDevices();
        LocalDateTime onlineThreshold = LocalDateTime.now().minusSeconds(ONLINE_WINDOW_SECONDS);
        long totalInBps = 0;
        long totalOutBps = 0;
        int onlineCount = 0;
        int anomalyCount = 0;

        for (TrafficData td : latest) {
            // 仅当最近一次采集在时间窗内才计为在线，设备停止后旧记录不再被算作在线
            boolean online = td.getCollectTime() != null && td.getCollectTime().isAfter(onlineThreshold);
            if (online) {
                onlineCount++;
                // 流量与异常统计同样只计在线设备，避免离线后大盘仍显示陈旧流量
                if (td.getInBps() != null) totalInBps += td.getInBps();
                if (td.getOutBps() != null) totalOutBps += td.getOutBps();
                if (td.getIsAnomaly() != null && td.getIsAnomaly() == 1) anomalyCount++;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("totalInBps", totalInBps);
        result.put("totalOutBps", totalOutBps);
        result.put("onlineCount", onlineCount);
        result.put("anomalyCount", anomalyCount);
        // 设备总数以设备注册表为准（从未上报过流量的新设备也计入）
        result.put("totalDevices", deviceRepository.count());
        return result;
    }

    /** 获取全网历史总流量（从系统启动至今，按采集时间聚合） */
    @GetMapping("/history-total")
    public List<Map<String, Object>> getHistoryTotal() {
        List<Object[]> rows = trafficDataRepository.findTotalTrafficByTime();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new HashMap<>();
            item.put("time", row[0]);
            item.put("totalIn", row[1]);
            item.put("totalOut", row[2]);
            result.add(item);
        }
        return result;
    }
}
