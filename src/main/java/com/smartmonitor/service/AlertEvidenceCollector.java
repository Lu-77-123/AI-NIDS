package com.smartmonitor.service;

import com.smartmonitor.entity.AlertInfo;
import com.smartmonitor.entity.Device;
import com.smartmonitor.entity.TrafficData;
import com.smartmonitor.repository.AlertInfoRepository;
import com.smartmonitor.repository.DeviceRepository;
import com.smartmonitor.repository.TrafficDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警研判取证器。
 *
 * 大模型如果只看到单条告警的四个字段，很容易给出"看起来专业但无法核实"的结论。
 * 本类在调用模型之前，先从数据库把可以核实的客观事实捞出来：
 *   - 该设备最近一个窗口内的流量分布（中位数、峰值、当前值所处位置）
 *   - 异常是否持续、当前值是否窗口内最高
 *   - 同设备历史告警的次数、类型分布与最近一次时间
 *   - 设备本身的性质（路由器/交换机、备注中是否为核心设备）
 *   - 同期全网处于异常状态的设备数量（用于判断是个例还是面状事件）
 *
 * 这些证据会被拼进提示词，并要求模型在输出中逐条引用，从而把"蒙"变成"有据可依"。
 */
@Component
public class AlertEvidenceCollector {

    private static final Logger logger = LoggerFactory.getLogger(AlertEvidenceCollector.class);

    /** 取证窗口：取最近多少条采集记录参与统计（与检测窗口默认值一致） */
    private static final int TRAFFIC_WINDOW = 30;
    /** 历史告警回溯时长 */
    private static final int HISTORY_HOURS = 24;

    private final TrafficDataRepository trafficDataRepository;
    private final AlertInfoRepository alertInfoRepository;
    private final DeviceRepository deviceRepository;

    public AlertEvidenceCollector(TrafficDataRepository trafficDataRepository,
                                  AlertInfoRepository alertInfoRepository,
                                  DeviceRepository deviceRepository) {
        this.trafficDataRepository = trafficDataRepository;
        this.alertInfoRepository = alertInfoRepository;
        this.deviceRepository = deviceRepository;
    }

    /**
     * 收集一条告警的全部客观证据。任何单项查询失败都不会中断研判，只是该证据缺失。
     */
    public Evidence collect(AlertInfo alert) {
        Evidence ev = new Evidence();
        ev.currentInBps = alert.getInBps() != null ? alert.getInBps() : 0L;
        ev.currentOutBps = alert.getOutBps() != null ? alert.getOutBps() : 0L;
        ev.anomalyScore = alert.getAnomalyScore() != null ? alert.getAnomalyScore() : 0.0;

        collectTrafficEvidence(alert.getDeviceId(), ev);
        collectAlertHistory(alert, ev);
        collectDeviceProfile(alert.getDeviceId(), ev);
        collectNetworkWide(ev);
        collectTopology(alert, ev);

        logger.info("研判取证完成: alertId={}, 样本={}, 中位数={}bps, 当前={}bps, 历史告警={}次",
                alert.getId(), ev.sampleCount, ev.medianInBps, ev.currentInBps, ev.alertCountSameDevice);
        return ev;
    }

    private void collectTrafficEvidence(Long deviceId, Evidence ev) {
        if (deviceId == null) return;
        try {
            List<TrafficData> desc = trafficDataRepository.findTop200ByDeviceIdOrderByCollectTimeDesc(deviceId);
            if (desc == null || desc.isEmpty()) return;

            int win = Math.min(TRAFFIC_WINDOW, desc.size());
            List<TrafficData> window = new ArrayList<>(desc.subList(0, win));
            ev.sampleCount = window.size();

            List<Long> values = new ArrayList<>(window.size());
            long max = 0L;
            for (TrafficData t : window) {
                long v = t.getInBps() != null ? t.getInBps() : 0L;
                values.add(v);
                if (v > max) max = v;
            }
            ev.maxInBps = max;
            ev.medianInBps = medianOf(values);
            ev.highestInWindow = ev.currentInBps >= max;
            ev.currentVsMedian = ev.medianInBps > 0
                    ? (double) ev.currentInBps / ev.medianInBps : 0.0;

            // 连续高于中位数 2 倍的轮数（按时间升序回溯，遇到不满足即停）
            Collections.reverse(window);
            int streak = 0;
            for (int i = window.size() - 1; i >= 0; i--) {
                long v = window.get(i).getInBps() != null ? window.get(i).getInBps() : 0L;
                if (ev.medianInBps > 0 && v >= ev.medianInBps * 2) {
                    streak++;
                } else {
                    break;
                }
            }
            ev.consecutiveAbove = streak;
        } catch (Exception e) {
            logger.warn("取证失败(流量窗口): deviceId={}, err={}", deviceId, e.getMessage());
        }
    }

    private void collectAlertHistory(AlertInfo alert, Evidence ev) {
        if (alert.getDeviceId() == null) return;
        try {
            List<AlertInfo> history = alertInfoRepository
                    .findByDeviceIdOrderByAlertTimeDesc(alert.getDeviceId());
            if (history == null) return;

            ev.alertCountSameDevice = history.size();
            LocalDateTime since = LocalDateTime.now().minusHours(HISTORY_HOURS);
            Map<String, Long> typeCount = new HashMap<>();
            AlertInfo latestOther = null;
            for (AlertInfo a : history) {
                if (a.getId() != null && a.getId().equals(alert.getId())) continue;
                if (a.getAlertTime() != null && a.getAlertTime().isAfter(since)) {
                    ev.alertCount24h++;
                }
                String type = a.getAttackType() != null ? a.getAttackType() : "未知";
                typeCount.merge(type, 1L, Long::sum);
                if (latestOther == null && a.getAlertTime() != null) {
                    latestOther = a;
                }
            }
            ev.typeDistribution = typeCount;
            if (latestOther != null) {
                ev.lastAlertSummary = latestOther.getAlertTime() + " " + latestOther.getAttackType();
            }
        } catch (Exception e) {
            logger.warn("取证失败(历史告警): deviceId={}, err={}", alert.getDeviceId(), e.getMessage());
        }
    }

    private void collectDeviceProfile(Long deviceId, Evidence ev) {
        if (deviceId == null) return;
        try {
            Device d = deviceRepository.findById(deviceId).orElse(null);
            if (d == null) return;
            ev.deviceType = d.getType();
            ev.deviceName = d.getName();
            ev.deviceRemark = d.getRemark();
        } catch (Exception e) {
            logger.warn("取证失败(设备画像): deviceId={}, err={}", deviceId, e.getMessage());
        }
    }

    private void collectNetworkWide(Evidence ev) {
        try {
            List<TrafficData> latest = trafficDataRepository.findLatestForAllDevices();
            if (latest == null) return;
            int anomaly = 0;
            for (TrafficData t : latest) {
                if (t.getIsAnomaly() != null && t.getIsAnomaly() == 1) anomaly++;
            }
            ev.anomalyDeviceCount = anomaly;
            ev.totalDeviceCount = latest.size();
        } catch (Exception e) {
            logger.warn("取证失败(全网对比): {}", e.getMessage());
        }
    }

    /**
     * 拓扑关系取证：系统没有采集 LLDP/ARP 邻居，无法给出物理链路，
     * 因此这里基于设备类型、备注与 IP 网段推导"网络位置"和"影响面"，
     * 供模型判断该告警的影响范围（核心出口 vs 单台终端）。
     */
    private void collectTopology(AlertInfo alert, Evidence ev) {
        try {
            List<Device> all = deviceRepository.findAll();
            ev.managedDeviceCount = all.size();

            Device self = null;
            for (Device d : all) {
                if (d.getId() != null && d.getId().equals(alert.getDeviceId())) {
                    self = d;
                    break;
                }
            }

            ev.topologyRole = roleOf(self);
            ev.networkScope = scopeOf(ev.topologyRole);

            String ip = alert.getDeviceIp() != null ? alert.getDeviceIp() : (self != null ? self.getIp() : null);
            if (ip != null && ip.contains(".")) {
                String prefix = ip.substring(0, ip.lastIndexOf('.'));
                ev.subnetPrefix = prefix + ".0/24";
                for (Device d : all) {
                    if (self != null && d.getId() != null && d.getId().equals(self.getId())) continue;
                    if (d.getIp() != null && d.getIp().startsWith(prefix + ".")) {
                        ev.sameSubnetNeighbors.add(labelOf(d));
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("取证失败(拓扑关系): {}", e.getMessage());
        }
    }

    private static String roleOf(Device d) {
        if (d == null) return "未知设备";
        String type = d.getType() != null ? d.getType() : "";
        String remark = d.getRemark() != null ? d.getRemark() : "";
        if (remark.contains("核心") || remark.contains("出口") || remark.contains("网关")) {
            return "核心出口设备";
        }
        if (type.contains("路由") || type.toLowerCase().contains("router")) {
            return "路由设备（可能承担网关）";
        }
        if (type.contains("交换") || type.toLowerCase().contains("switch")) {
            return "交换设备";
        }
        if (type.contains("服务") || type.toLowerCase().contains("server")) {
            return "业务服务器";
        }
        return "终端设备";
    }

    private static String scopeOf(String role) {
        if ("核心出口设备".equals(role)) return "高：异常可能影响其下游全部业务流量，需优先处置";
        if (role != null && role.startsWith("路由")) return "中高：影响所连接网段的跨网段通信";
        if ("交换设备".equals(role)) return "中：影响本交换机下挂终端";
        if ("业务服务器".equals(role)) return "中：影响该服务器承载的业务";
        return "低：影响单台终端";
    }

    private static String labelOf(Device d) {
        String name = d.getName() != null && !d.getName().trim().isEmpty() ? d.getName() : d.getIp();
        return name + "(" + d.getIp() + ")";
    }

    private static long medianOf(List<Long> values) {        if (values == null || values.isEmpty()) return 0L;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /** 取证结果：全部为可核实的客观事实，不含推断 */
    public static class Evidence {
        public long currentInBps;
        public long currentOutBps;
        public double anomalyScore;
        public int sampleCount;
        public long medianInBps;
        public long maxInBps;
        public boolean highestInWindow;
        public double currentVsMedian;
        public int consecutiveAbove;
        public int alertCountSameDevice;
        public int alertCount24h;
        public String lastAlertSummary;
        public Map<String, Long> typeDistribution;
        public String deviceType;
        public String deviceName;
        public String deviceRemark;
        public int anomalyDeviceCount;
        public int totalDeviceCount;
        public String topologyRole;
        public String networkScope;
        public String subnetPrefix;
        public int managedDeviceCount;
        public List<String> sameSubnetNeighbors = new ArrayList<>();

        /** 拼成提示词中的"客观证据"段落，每条都带序号，便于模型引用 */
        public String toPromptText() {
            StringBuilder sb = new StringBuilder("【系统取证结果（客观事实，引用时请标注序号）】\n");
            int idx = 1;

            if (sampleCount > 0) {
                sb.append(idx++).append(". 该设备最近 ").append(sampleCount).append(" 轮采集中，入向速率中位数 ")
                        .append(medianInBps).append(" bps，窗口内峰值 ").append(maxInBps)
                        .append(" bps，当前 ").append(currentInBps).append(" bps");
                if (medianInBps > 0) {
                    sb.append("，为基线的 ").append(String.format("%.1f", currentVsMedian)).append(" 倍");
                }
                sb.append('\n');
                if (highestInWindow) {
                    sb.append(idx++).append(". 当前速率为该窗口内的最高值\n");
                } else {
                    sb.append(idx++).append(". 当前值不是窗口内最高值，历史上出现过更高流量\n");
                }
                if (consecutiveAbove >= 2) {
                    sb.append(idx++).append(". 异常已持续 ").append(consecutiveAbove)
                            .append(" 轮采集（连续高于基线 2 倍），非单点抖动\n");
                } else {
                    sb.append(idx++).append(". 异常仅出现在当前这一轮，属单点突增\n");
                }
            } else {
                sb.append(idx++).append(". 该设备暂无历史流量样本，无法判断基线偏移\n");
            }

            if (alertCountSameDevice > 0) {
                sb.append(idx++).append(". 该设备历史告警共 ").append(alertCountSameDevice)
                        .append(" 次，近 ").append(HISTORY_HOURS).append(" 小时 ").append(alertCount24h).append(" 次");
                if (typeDistribution != null && !typeDistribution.isEmpty()) {
                    sb.append("，类型分布：").append(typeDistribution);
                }
                if (lastAlertSummary != null) {
                    sb.append("，最近一次：").append(lastAlertSummary);
                }
                sb.append('\n');
            } else {
                sb.append(idx++).append(". 该设备此前无任何告警记录，本次为首发\n");
            }

            if (deviceType != null || deviceRemark != null) {
                sb.append(idx++).append(". 设备性质：").append(deviceType != null ? deviceType : "未知");
                if (deviceRemark != null && !deviceRemark.trim().isEmpty()) {
                    sb.append("，备注：").append(deviceRemark);
                }
                sb.append('\n');
            }

            if (totalDeviceCount > 0) {
                sb.append(idx++).append(". 同期全网 ").append(totalDeviceCount)
                        .append(" 台在线设备中，处于异常状态的有 ").append(anomalyDeviceCount).append(" 台\n");
            }

            if (topologyRole != null) {
                sb.append(idx++).append(". 网络位置：").append(topologyRole)
                        .append("；影响面评估：").append(networkScope).append('\n');
            }
            if (subnetPrefix != null) {
                sb.append(idx++).append(". 同网段（").append(subnetPrefix).append("）内另有 ")
                        .append(sameSubnetNeighbors.size()).append(" 台纳管设备");
                if (!sameSubnetNeighbors.isEmpty()) {
                    int limit = Math.min(5, sameSubnetNeighbors.size());
                    sb.append("：").append(String.join("、", sameSubnetNeighbors.subList(0, limit)));
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }
}
