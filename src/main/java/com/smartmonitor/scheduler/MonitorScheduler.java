package com.smartmonitor.scheduler;

import com.smartmonitor.entity.Device;
import com.smartmonitor.entity.TrafficData;
import com.smartmonitor.repository.DeviceRepository;
import com.smartmonitor.repository.TrafficDataRepository;
import com.smartmonitor.service.AiDetectorService;
import com.smartmonitor.service.AlertService;
import com.smartmonitor.service.DeviceService;
import com.smartmonitor.service.SnmpService;
import com.smartmonitor.websocket.MetricsWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 定时任务调度器：
 * 1. 每 5 分钟做一次设备状态采集（SNMP sysName / sysUpTime）
 * 2. 每 10 秒做一次流量采集（SNMP ifInOctets / ifOutOctets），计算速率并推送 WebSocket
 */
@Component
public class MonitorScheduler {

    private static final Logger logger = LoggerFactory.getLogger(MonitorScheduler.class);
    private static final long COUNTER_32_MAX = 0xFFFFFFFFL; // 2^32 - 1
    /** 单接口速率的合理上限：10 Gbps，超出即视为采集异常（计数器重置/回绕误判） */
    private static final long MAX_PLAUSIBLE_BPS = 10_000_000_000L;

    private final DeviceService deviceService;
    private final DeviceRepository deviceRepository;
    private final SnmpService snmpService;
    private final TrafficDataRepository trafficDataRepository;
    private final MetricsWebSocketHandler webSocketHandler;
    private final AiDetectorService aiDetectorService;

    /** 流量并发采集线程池：每台设备独立 SNMP 轮询，单台离线超时不阻塞整轮（保证 10s 推送节奏） */
    private final ExecutorService trafficExecutor = Executors.newFixedThreadPool(16);
    private final AlertService alertService;
    private final com.smartmonitor.service.AgentDecisionEngine agentDecisionEngine;

    public MonitorScheduler(DeviceService deviceService,
                            DeviceRepository deviceRepository,
                            SnmpService snmpService,
                            TrafficDataRepository trafficDataRepository,
                            MetricsWebSocketHandler webSocketHandler,
                            AiDetectorService aiDetectorService,
                            AlertService alertService,
                            com.smartmonitor.service.AgentDecisionEngine agentDecisionEngine) {
        this.deviceService = deviceService;
        this.deviceRepository = deviceRepository;
        this.snmpService = snmpService;
        this.trafficDataRepository = trafficDataRepository;
        this.webSocketHandler = webSocketHandler;
        this.aiDetectorService = aiDetectorService;
        this.alertService = alertService;
        this.agentDecisionEngine = agentDecisionEngine;
    }

    /** 每 60 秒并发采集一次设备状态并持久化（列表接口只查库，保证秒开） */
    @Scheduled(fixedRate = 60000)
    public void monitorDevice() {
        try {
            deviceService.refreshRealtime();
            logger.info("设备状态刷新完成");
        } catch (Exception e) {
            logger.error("设备状态刷新异常", e);
        }
    }

    /** 每 10 秒并发采集一次流量数据，计算速率，存入数据库并推送 WebSocket */
    @Scheduled(fixedRate = 10000)
    public void collectTraffic() {
        List<Device> devices = deviceRepository.findAll();
        if (devices.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        // 并发采集：每台设备独立 SNMP 轮询，单台离线（最长超时约 6s）不再拖慢整轮
        List<Future<TrafficData>> futures = new ArrayList<>(devices.size());
        for (Device device : devices) {
            futures.add(trafficExecutor.submit(() -> collectOneDevice(device, now)));
        }

        List<Map<String, Object>> wsDevices = new ArrayList<>();
        List<TrafficData> savedTrafficList = new ArrayList<>();

        // 按提交顺序回收结果；返回 null 表示设备离线
        for (int i = 0; i < devices.size(); i++) {
            Device device = devices.get(i);
            TrafficData td = null;
            try {
                td = futures.get(i).get(8, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warn("流量采集等待异常 - 设备 {}: {}", device.getIp(), e.getMessage());
            }
            if (td == null) {
                // 设备不可达：明确推送离线状态
                Map<String, Object> wsDev = new LinkedHashMap<>();
                wsDev.put("deviceId", device.getId());
                wsDev.put("deviceName", device.getRemark() != null ? device.getRemark() : device.getIp());
                wsDev.put("online", false);
                wsDev.put("inBps", 0);
                wsDev.put("outBps", 0);
                wsDev.put("anomalyScore", 0.0);
                wsDev.put("isAnomaly", 0);
                wsDevices.add(wsDev);
            } else {
                savedTrafficList.add(td);
            }
        }

        // 调用 AI 服务进行批量异常检测
        if (!savedTrafficList.isEmpty()) {
            try {
                Map<Long, double[]> aiResults = aiDetectorService.detectBatch(savedTrafficList);
                // 更新异常分并构建 WebSocket 数据
                for (TrafficData td : savedTrafficList) {
                    double[] result = aiResults.get(td.getDeviceId());
                    double score = (result != null) ? result[0] : 0.0;
                    int isAnomaly = (result != null) ? (int) result[1] : 0;

                    td.setAnomalyScore(score);
                    td.setIsAnomaly(isAnomaly);
                    trafficDataRepository.save(td);

                    Map<String, Object> wsDev = new LinkedHashMap<>();
                    wsDev.put("deviceId", td.getDeviceId());
                    wsDev.put("deviceName", td.getDeviceName());
                    wsDev.put("online", true);
                    wsDev.put("inBps", td.getInBps());
                    wsDev.put("outBps", td.getOutBps());
                    wsDev.put("anomalyScore", score);
                    wsDev.put("isAnomaly", isAnomaly);
                    wsDevices.add(wsDev);
                }
                int anomalyCount = (int) savedTrafficList.stream()
                        .filter(t -> t.getIsAnomaly() != null && t.getIsAnomaly() == 1).count();
                logger.info("AI 检测完成: {} 台设备, 异常 {} 台", savedTrafficList.size(), anomalyCount);

                // 为异常设备生成入侵告警
                for (TrafficData td : savedTrafficList) {
                    if (td.getIsAnomaly() != null && td.getIsAnomaly() == 1) {
                        try {
                            com.smartmonitor.entity.AlertInfo alert = alertService.createAlert(
                                td.getDeviceId(), td.getDeviceName(), td.getDeviceIp(),
                                td.getInBps() != null ? td.getInBps() : 0,
                                td.getOutBps() != null ? td.getOutBps() : 0,
                                td.getAnomalyScore() != null ? td.getAnomalyScore() : 0.0
                            );
                            // 告警被冷却抑制时返回 null，跳过智能体评估
                            if (alert != null) {
                                // 智能体评估告警并生成响应决策
                                try {
                                    List<com.smartmonitor.entity.AgentAction> actions = agentDecisionEngine.evaluate(alert);
                                    if (!actions.isEmpty()) {
                                        logger.info("智能体生成 {} 条响应决策", actions.size());
                                    }
                                } catch (Exception agex) {
                                    logger.warn("智能体评估失败: {}", agex.getMessage());
                                }
                            }
                        } catch (Exception ex) {
                            logger.warn("告警生成失败: {}", ex.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("AI 检测异常，仅推送基础数据: {}", e.getMessage());
                for (TrafficData td : savedTrafficList) {
                    Map<String, Object> wsDev = new LinkedHashMap<>();
                    wsDev.put("deviceId", td.getDeviceId());
                    wsDev.put("deviceName", td.getDeviceName());
                    wsDev.put("online", true);
                    wsDev.put("inBps", td.getInBps());
                    wsDev.put("outBps", td.getOutBps());
                    wsDev.put("anomalyScore", 0.0);
                    wsDev.put("isAnomaly", 0);
                    wsDevices.add(wsDev);
                }
            }
        }

        // 推送 WebSocket
        if (!wsDevices.isEmpty()) {
            Map<String, Object> wsMessage = new LinkedHashMap<>();
            wsMessage.put("timestamp", System.currentTimeMillis());
            wsMessage.put("devices", wsDevices);
            webSocketHandler.broadcast(wsMessage);
        }
    }

    /**
     * 采集单台设备流量、计算速率并落库。
     * @return 保存后的 TrafficData；设备不可达或采集异常时返回 null（由调用方标记离线）
     */
    private TrafficData collectOneDevice(Device device, LocalDateTime now) {
        try {
            long[] traffic = snmpService.collectTraffic(device.getIp(), device.getCommunity());
            if (traffic == null) {
                return null;
            }

            long inOctets = traffic[0];
            long outOctets = traffic[1];

            // 查询上次采集记录，计算速率
            TrafficData last = trafficDataRepository.findFirstByDeviceIdOrderByCollectTimeDesc(device.getId());
            long inBps = 0;
            long outBps = 0;
            if (last != null && last.getCollectTime() != null) {
                long seconds = Duration.between(last.getCollectTime(), now).getSeconds();
                if (seconds > 0) {
                    inBps = toBps(inOctets, last.getInOctets(), seconds, device.getIp(), "入向");
                    outBps = toBps(outOctets, last.getOutOctets(), seconds, device.getIp(), "出向");
                }
            }

            TrafficData td = new TrafficData();
            td.setDeviceId(device.getId());
            td.setDeviceIp(device.getIp());
            td.setDeviceName(device.getRemark() != null ? device.getRemark() : device.getIp());
            td.setInOctets(inOctets);
            td.setOutOctets(outOctets);
            td.setInBps(inBps);
            td.setOutBps(outBps);
            td.setIsAnomaly(0);
            td.setAnomalyScore(0.0);
            td.setCollectTime(now);
            return trafficDataRepository.save(td);
        } catch (Exception e) {
            logger.warn("流量采集异常 - 设备 {}: {}", device.getIp(), e.getMessage());
            return null;
        }
    }

    /**
     * 由相邻两次 Counter32 采样值计算速率（bps）。
     *
     * 计数器比上次小有两种成因：真正的 32 位回绕（累计到 2^32 后归零）与计数器被重置
     * （接口 flap、设备重启、模块热插拔）。二者数值特征一致，但只有一个采样周期内走完
     * 2^32 字节才可能是真回绕——千兆链路满速也需要 34 秒，而采集周期只有 10 秒。因此
     * 以上次值是否越过 2^31 作为判据：不足一半时一律判为计数器重置，本轮速率记 0，
     * 避免把重置误算成几百 Mbps 的假峰值并触发误告警。
     *
     * @param current  本轮计数器值
     * @param previous 上轮计数器值，可能为 null
     * @param seconds  两次采集的间隔秒数
     */
    private long toBps(long current, Long previous, long seconds, String ip, String direction) {
        if (previous == null || seconds <= 0) {
            return 0;
        }
        long delta;
        if (current >= previous) {
            delta = current - previous;
        } else if (previous > COUNTER_32_MAX / 2) {
            delta = current + (COUNTER_32_MAX + 1) - previous;
        } else {
            logger.warn("设备 {} 的{}计数器被重置（{} → {}），本轮速率记为 0", ip, direction, previous, current);
            return 0;
        }
        long bps = delta * 8 / seconds;
        if (bps > MAX_PLAUSIBLE_BPS) {
            logger.warn("设备 {} 的{}速率 {} bps 超出单接口合理上限，判定为无效采样，记为 0", ip, direction, bps);
            return 0;
        }
        return bps;
    }
}
