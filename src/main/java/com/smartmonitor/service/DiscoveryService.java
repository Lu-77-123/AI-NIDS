package com.smartmonitor.service;

import com.smartmonitor.entity.Device;
import com.smartmonitor.repository.DeviceRepository;
import com.smartmonitor.websocket.MetricsWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网络自动发现服务。
 *
 * 流程：
 *   1. 自动检测本机所在网段（遍历网卡找非 loopback 的 IPv4）
 *   2. 对网段内所有 IP 并行发送 SNMP GET（sysName + sysDescr）
 *   3. 有响应的视为 SNMP 设备，根据 sysDescr 智能识别厂商和类型
 *   4. 新设备自动写入 device 表，已存在的跳过
 *   5. 通过 WebSocket 实时推送扫描进度和发现结果
 */
@Service
public class DiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(DiscoveryService.class);

    /** SNMP 探测超时（毫秒）— Docker 内网延迟低，1.5 秒足够 */
    private static final int PROBE_TIMEOUT_MS = 1500;
    /** 并发探测线程数 — 提升到 50 线程，Docker 网络可承受 */
    private static final int THREAD_POOL_SIZE = 50;
    /** 单 IP 重试次数 — 首次失败后再重试 1 次 */
    private static final int PROBE_RETRIES = 1;
    /** 默认社区字符串兜底（优先使用 snmp.community 配置，可被环境变量 SNMP_COMMUNITY 覆盖） */
    private static final String FALLBACK_COMMUNITY = "public";

    /**
     * 真实模式配置的扫描网段（x.x.x.x/24）。
     * 后端容器内网卡只能看到 Docker 内网（172.21.0.x），采集 eNSP/真实设备时
     * 必须通过环境变量 SNMP_DISCOVERY_SEGMENT 显式指定目标网段；留空才回退到网卡自动探测。
     */
    @Value("${snmp.discovery.segment:}")
    private String configuredSegment;

    /** 默认 SNMP 社区字符串（eNSP 设备 snmp-agent community 配置的值） */
    @Value("${snmp.community:public}")
    private String configuredCommunity;

    private final SnmpService snmpService;
    private final DeviceRepository deviceRepository;
    private final MetricsWebSocketHandler webSocketHandler;

    /** 扫描状态标志，防止重复扫描 */
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    /** 最近一次扫描的结果缓存（供前端刷新/晚进入页面时拉取）*/
    private final List<DiscoveredDevice> lastResultCache = Collections.synchronizedList(new ArrayList<>());
    /** 最近一次扫描的进度缓存 */
    private volatile Map<String, Object> lastProgressCache = null;
    /** 最近一次扫描是否已完成 */
    private volatile boolean lastScanDone = false;

    public DiscoveryService(SnmpService snmpService,
                            DeviceRepository deviceRepository,
                            MetricsWebSocketHandler webSocketHandler) {
        this.snmpService = snmpService;
        this.deviceRepository = deviceRepository;
        this.webSocketHandler = webSocketHandler;
    }

    /** 是否正在扫描 */
    public boolean isScanning() {
        return scanning.get();
    }

    /** 配置的默认社区字符串 */
    public String getDefaultCommunity() {
        return (configuredCommunity == null || configuredCommunity.trim().isEmpty())
                ? FALLBACK_COMMUNITY : configuredCommunity.trim();
    }

    /** 获取最近一次扫描结果缓存（前端进入页面/刷新时拉取）*/
    public Map<String, Object> getLastResult() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("scanning", scanning.get());
        res.put("scanDone", lastScanDone);
        res.put("progress", lastProgressCache);
        res.put("devices", new ArrayList<>(lastResultCache));
        return res;
    }

    /**
     * 自动检测本机所在网段，返回 "x.x.x.x/24" 格式。
     * 遍历所有网卡，取第一个非 loopback / 非 docker0 的 IPv4 地址。
     */
    public String detectNetworkSegment() {
        // 真实模式：优先使用环境变量显式配置的网段（eNSP/真实设备所在网段）
        if (configuredSegment != null && !configuredSegment.trim().isEmpty()) {
            String seg = configuredSegment.trim();
            logger.info("使用配置的 SNMP 扫描网段: {}", seg);
            return seg;
        }
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                String name = nic.getName().toLowerCase();
                // 跳过 docker0 / veth 等虚拟网卡，优先选 eth0 / ens33
                if (name.startsWith("docker") || name.startsWith("veth") || name.startsWith("br-")) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        String segment = ip.substring(0, ip.lastIndexOf('.')) + ".0/24";
                        logger.info("检测到网段: {} (网卡: {})", segment, nic.getName());
                        return segment;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("网段检测失败", e);
        }
        // 兜底：Docker 默认网段
        return "172.18.0.0/24";
    }

    /**
     * 启动异步网络扫描。
     * 扫描完成后通过 WebSocket 推送 "discovery_done" 事件。
     *
     * @param segment   网段，如 "172.18.0.0/24"
     * @param community SNMP 社区字符串
     */
    public void startDiscovery(String segment, String community) {
        if (!scanning.compareAndSet(false, true)) {
            logger.warn("扫描已在进行中，跳过");
            return;
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                doDiscovery(segment, community);
            } catch (Exception e) {
                logger.error("网络扫描异常", e);
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("type", "discovery_error");
                err.put("message", e.getMessage());
                webSocketHandler.broadcast(err);
            } finally {
                scanning.set(false);
            }
        });
        executor.shutdown();
    }

    /** 实际扫描逻辑 */
    private void doDiscovery(String segment, String community) {
        // 解析网段，生成 IP 列表
        String baseIp = segment.split("/")[0];
        String prefix = baseIp.substring(0, baseIp.lastIndexOf('.') + 1);
        int start = 1;
        int end = 254;
        int total = end - start + 1;

        logger.info("开始扫描网段: {} (共 {} 个 IP)", segment, total);

        // 清空上次缓存，重置状态
        lastResultCache.clear();
        lastScanDone = false;
        Map<String, Object> initProgress = new LinkedHashMap<>();
        initProgress.put("current", 0);
        initProgress.put("total", total);
        initProgress.put("found", 0);
        lastProgressCache = initProgress;

        // 推送扫描开始
        Map<String, Object> startMsg = new LinkedHashMap<>();
        startMsg.put("type", "discovery_start");
        startMsg.put("segment", segment);
        startMsg.put("total", total);
        webSocketHandler.broadcast(startMsg);

        // 并发探测
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        List<Future<DiscoveredDevice>> futures = new ArrayList<>();
        AtomicInteger progress = new AtomicInteger(0);
        List<DiscoveredDevice> found = Collections.synchronizedList(new ArrayList<>());

        for (int i = start; i <= end; i++) {
            final String ip = prefix + i;
            futures.add(pool.submit(() -> {
                try {
                    // 应用层重试：最多 PROBE_RETRIES + 1 次尝试，间隔 300ms 避免抖动
                    String[] info = null;
                    for (int attempt = 0; attempt <= PROBE_RETRIES; attempt++) {
                        info = snmpService.probeDevice(ip, community, PROBE_TIMEOUT_MS);
                        if (info != null) {
                            if (attempt > 0) {
                                logger.debug("IP {} 第 {} 次重试成功", ip, attempt);
                            }
                            break;
                        }
                        if (attempt < PROBE_RETRIES) {
                            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        }
                    }
                    if (info != null) {
                        DiscoveredDevice dd = new DiscoveredDevice();
                        dd.ip = ip;
                        dd.sysName = info[0];
                        dd.sysDescr = info[1];
                        dd.deviceType = identifyDeviceType(info[1]);
                        dd.vendor = identifyVendor(info[1]);
                        dd.existing = deviceRepository.findByIp(ip).isPresent();
                        found.add(dd);
                        // 更新结果缓存
                        lastResultCache.add(dd);

                        // 推送单个设备发现事件
                        Map<String, Object> devMsg = new LinkedHashMap<>();
                        devMsg.put("type", "discovery_device");
                        devMsg.put("ip", dd.ip);
                        devMsg.put("sysName", dd.sysName);
                        devMsg.put("sysDescr", dd.sysDescr);
                        devMsg.put("deviceType", dd.deviceType);
                        devMsg.put("vendor", dd.vendor);
                        devMsg.put("existing", dd.existing);
                        webSocketHandler.broadcast(devMsg);

                        logger.info("发现设备: {} - {} ({})", ip, dd.sysName, dd.deviceType);
                    }
                } catch (Exception e) {
                    // 忽略单个 IP 探测异常
                } finally {
                    int done = progress.incrementAndGet();
                    // 每探测 10 个推送一次进度
                    if (done % 10 == 0 || done == total) {
                        Map<String, Object> progMsg = new LinkedHashMap<>();
                        progMsg.put("type", "discovery_progress");
                        progMsg.put("current", done);
                        progMsg.put("total", total);
                        progMsg.put("found", found.size());
                        // 更新进度缓存
                        lastProgressCache = progMsg;
                        webSocketHandler.broadcast(progMsg);
                    }
                }
                return null;
            }));
        }

        // 等待所有探测完成（单 future 加超时 60s，整体加 awaitTermination 120s，防止个别 IP 阻塞导致整个扫描卡死）
        for (Future<DiscoveredDevice> f : futures) {
            try {
                f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                logger.warn("单个 IP 探测超时超过 60s，强制取消该任务");
                try { f.cancel(true); } catch (Exception ignored) {}
            } catch (Exception ignored) {
            }
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(120, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.warn("扫描线程池 120s 未优雅关闭，强制 shutdownNow");
                pool.shutdownNow();
            }
        } catch (InterruptedException ie) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 标记完成并更新缓存
        lastScanDone = true;
        Map<String, Object> finalProgress = new LinkedHashMap<>();
        finalProgress.put("current", total);
        finalProgress.put("total", total);
        finalProgress.put("found", found.size());
        lastProgressCache = finalProgress;

        // 推送扫描完成
        Map<String, Object> doneMsg = new LinkedHashMap<>();
        doneMsg.put("type", "discovery_done");
        doneMsg.put("segment", segment);
        doneMsg.put("total", total);
        doneMsg.put("found", found.size());
        doneMsg.put("devices", found);
        webSocketHandler.broadcast(doneMsg);

        logger.info("扫描完成: 共扫描 {} 个 IP，发现 {} 台 SNMP 设备", total, found.size());
    }

    /**
     * 将发现的设备批量加入 device 表。
     *
     * @param ips       要加入的 IP 列表
     * @param community SNMP 社区字符串
     * @return 新增的设备数量
     */
    public int addDiscoveredDevices(List<String> ips, String community) {
        int added = 0;
        for (String ip : ips) {
            if (ip == null || ip.isEmpty()) continue;
            // 双重校验：先查是否已存在，避免 UNIQUE 索引冲突
            if (deviceRepository.findByIp(ip).isPresent()) {
                logger.debug("IP {} 已存在，跳过重复加入", ip);
                continue;
            }
            String[] info = snmpService.probeDevice(ip, community, 2000);
            String name = (info != null && info[0] != null) ? info[0] : "未知设备";
            String descr = (info != null && info[1] != null) ? info[1] : "";
            String type = identifyDeviceType(descr);

            try {
                Device device = new Device(ip, type, community, name);
                deviceRepository.save(device);
                added++;
                logger.info("自动加入设备: {} ({})", ip, name);
            } catch (org.springframework.dao.DataIntegrityViolationException dive) {
                // 并发场景：刚好两个请求同时 save，UNIQUE 索引兜底。记录日志即可不中断流程
                logger.warn("IP {} 已被其他线程入库，跳过重复加入: {}", ip, dive.getMessage());
            } catch (Exception e) {
                logger.error("加入设备失败: {} - {}", ip, e.getMessage());
            }
        }
        return added;
    }

    /** 根据 sysDescr 识别设备类型 */
    private String identifyDeviceType(String sysDescr) {
        if (sysDescr == null || sysDescr.isEmpty()) {
            return "未知设备";
        }
        String lower = sysDescr.toLowerCase();
        if (lower.contains("router") || lower.contains("ar")) {
            return "路由器";
        }
        if (lower.contains("switch") || lower.contains("s5") || lower.contains("s3")) {
            return "交换机";
        }
        if (lower.contains("firewall") || lower.contains("usg")) {
            return "防火墙";
        }
        if (lower.contains("access point") || lower.contains("ap") || lower.contains("wifi")) {
            return "无线AP";
        }
        if (lower.contains("linux") || lower.contains("windows") || lower.contains("server")) {
            return "服务器";
        }
        return "网络设备";
    }

    /** 根据 sysDescr 识别厂商 */
    private String identifyVendor(String sysDescr) {
        if (sysDescr == null || sysDescr.isEmpty()) {
            return "未知";
        }
        String lower = sysDescr.toLowerCase();
        if (lower.contains("huawei") || lower.contains("vrp")) {
            return "华为";
        }
        if (lower.contains("cisco") || lower.contains("ios")) {
            return "思科";
        }
        if (lower.contains("h3c") || lower.contains("comware")) {
            return "H3C";
        }
        if (lower.contains("linux")) {
            return "Linux";
        }
        if (lower.contains("windows")) {
            return "Microsoft";
        }
        return "其他";
    }

    /** 发现的设备信息（内部类） */
    public static class DiscoveredDevice {
        public String ip;
        public String sysName;
        public String sysDescr;
        public String deviceType;
        public String vendor;
        public boolean existing;
    }
}
