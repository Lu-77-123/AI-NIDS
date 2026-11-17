package com.smartmonitor.service;

import org.pcap4j.core.PcapAddress;
import org.pcap4j.core.PcapHandle;
import org.pcap4j.core.PcapNetworkInterface;
import org.pcap4j.core.Pcaps;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.TcpPacket;
import org.pcap4j.packet.UdpPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 旁路抓包服务（Pcap4j）。
 *
 * 职责：在指定网卡上抓包 → 每秒聚合一次统计 → 交给 {@link PacketThreatDetector} 做包级特征判断
 *       → 检出威胁后记录（可通过 /api/capture/stats 查询）。
 *
 * 设计原则：**失败不影响主系统**。网卡找不到、抓包权限不足、pcap 驱动异常，
 * 都只在日志中记录，Spring 容器照常启动，SNMP 采集与统计检测链路不受影响。
 */
@Service
public class PcapCaptureService {

    private static final Logger log = LoggerFactory.getLogger(PcapCaptureService.class);

    private final PacketThreatDetector detector;

    @Value("${pcap.enabled:true}")
    private boolean enabled;

    /** 要抓包的网卡 IP（eNSP 桥接网卡一般为 192.168.99.1） */
    @Value("${pcap.interface-ip:192.168.99.1}")
    private String interfaceIp;

    /** 内存中最多保留多少条检出记录 */
    @Value("${pcap.max-threats:50}")
    private int maxThreats;

    private volatile boolean running = false;
    private Thread worker;
    private volatile String ifName = "";
    private volatile String ifDesc = "";

    private final AtomicLong wTotal = new AtomicLong();
    private final AtomicLong wTcp = new AtomicLong();
    private final AtomicLong wSyn = new AtomicLong();
    private final AtomicLong wUdp = new AtomicLong();

    /** 端口扫描滑动窗口：源 IP -> 端口集合 */
    private final Map<String, ScanWindow> scanMap = new ConcurrentHashMap<>();
    private final Deque<Map<String, Object>> threats = new ConcurrentLinkedDeque<>();
    private volatile Map<String, Object> latest = new LinkedHashMap<>();

    public PcapCaptureService(PacketThreatDetector detector) {
        this.detector = detector;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("Pcap 旁路抓包已禁用（pcap.enabled=false）");
            return;
        }
        worker = new Thread(this::loop, "pcap-capture");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** 抓包主循环（独立守护线程，异常只记录不抛出） */
    private void loop() {
        try {
            PcapNetworkInterface nif = selectInterface();
            if (nif == null) {
                log.warn("Pcap: 未找到匹配网卡（pcap.interface-ip={}），旁路抓包未启动", interfaceIp);
                return;
            }
            ifDesc = nif.getDescription() == null ? "" : nif.getDescription();
            ifName = nif.getName();

            try (PcapHandle handle = nif.openLive(
                    65536,
                    PcapNetworkInterface.PromiscuousMode.PROMISCUOUS,
                    10)) {
                running = true;
                log.info("Pcap 旁路抓包启动: {} ({})，监听 IP={}", ifDesc, ifName, interfaceIp);

                long winStart = System.currentTimeMillis();
                while (running) {
                    Packet packet;
                    try {
                        packet = handle.getNextPacketEx();
                    } catch (TimeoutException te) {
                        packet = null; // 10ms 无包，继续
                    }
                    if (packet != null) {
                        account(packet);
                    }
                    long now = System.currentTimeMillis();
                    if (now - winStart >= 1000) {
                        flush(now - winStart);
                        winStart = now;
                    }
                }
            }
        } catch (Throwable e) {
            running = false;
            log.warn("Pcap 抓包线程结束（不影响主系统）: {}", e.toString());
        }
    }

    /** 按 IP 精确匹配网卡；找不到再退回环回适配器；最后退第一个网卡 */
    private PcapNetworkInterface selectInterface() {
        try {
            List<PcapNetworkInterface> all = Pcaps.findAllDevs();
            if (all == null || all.isEmpty()) {
                return null;
            }
            if (interfaceIp != null && !interfaceIp.trim().isEmpty()) {
                for (PcapNetworkInterface dev : all) {
                    for (PcapAddress addr : dev.getAddresses()) {
                        if (addr.getAddress() != null && interfaceIp.equals(addr.getAddress().getHostAddress())) {
                            return dev;
                        }
                    }
                }
            }
            for (PcapNetworkInterface dev : all) {
                String desc = dev.getDescription() == null ? "" : dev.getDescription().toLowerCase();
                if (desc.contains("loopback") || desc.contains("环回") || desc.contains("km-test")) {
                    return dev;
                }
            }
            return all.get(0);
        } catch (Throwable e) {
            log.warn("Pcap 枚举网卡失败: {}", e.toString());
            return null;
        }
    }

    /** 统计单个报文 */
    private void account(Packet packet) {
        wTotal.incrementAndGet();

        TcpPacket tcp = packet.get(TcpPacket.class);
        if (tcp != null) {
            wTcp.incrementAndGet();
            if (tcp.getHeader().getSyn() && !tcp.getHeader().getAck()) {
                wSyn.incrementAndGet();
            }
            IpV4Packet ip = packet.get(IpV4Packet.class);
            if (ip != null) {
                String src = ip.getHeader().getSrcAddr().getHostAddress();
                int dstPort = tcp.getHeader().getDstPort().valueAsInt();
                scanMap.computeIfAbsent(src, k -> new ScanWindow()).add(dstPort);
            }
        }
        if (packet.get(UdpPacket.class) != null) {
            wUdp.incrementAndGet();
        }
    }

    /** 每秒结算一次：做特征判断 + 刷新快照 */
    private void flush(long elapsedMs) {
        long total = wTotal.getAndSet(0);
        long tcp = wTcp.getAndSet(0);
        long syn = wSyn.getAndSet(0);
        long udp = wUdp.getAndSet(0);
        double secs = Math.max(0.5, elapsedMs / 1000.0);

        Map<String, Object> t = detector.detect(secs, total, tcp, syn, udp);
        if (t != null) {
            push(t);
        }

        // 端口扫描：清理过期窗口 + 判断未上报过的
        for (Map.Entry<String, ScanWindow> e : new ArrayList<>(scanMap.entrySet())) {
            ScanWindow w = e.getValue();
            if (w.isExpired()) {
                scanMap.remove(e.getKey());
                continue;
            }
            Map<String, Object> s = detector.detectPortScan(e.getKey(), w.portCount(), w.seconds());
            if (s != null && w.markReported()) {
                push(s);
            }
        }

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("running", running);
        snap.put("interface", ifDesc);
        snap.put("pps", Math.round(total / secs));
        snap.put("tcpPps", Math.round(tcp / secs));
        snap.put("synPps", Math.round(syn / secs));
        snap.put("udpPps", Math.round(udp / secs));
        snap.put("time", java.time.LocalDateTime.now().toString());
        latest = snap;
    }

    private void push(Map<String, Object> threat) {
        threat.putIfAbsent("time", java.time.LocalDateTime.now().toString());
        threats.addFirst(threat);
        while (threats.size() > maxThreats) {
            threats.removeLast();
        }
        log.info("Pcap 检出威胁: {} - {}", threat.get("attackType"), threat.get("description"));
    }

    /** 供 /api/capture/stats 查询 */
    public Map<String, Object> getStats() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("enabled", enabled);
        res.put("running", running);
        res.put("interface", ifDesc);
        res.put("ifName", ifName);
        res.put("interfaceIp", interfaceIp);
        res.put("latest", latest);
        res.put("threats", new ArrayList<>(threats));
        return res;
    }

    /** 端口探测滑动窗口 */
    private static class ScanWindow {
        private final Set<Integer> ports = ConcurrentHashMap.newKeySet();
        private final long start = System.currentTimeMillis();
        private volatile long last = start;
        private volatile boolean reported;

        void add(int port) {
            ports.add(port);
            last = System.currentTimeMillis();
        }

        int portCount() {
            return ports.size();
        }

        long seconds() {
            return Math.max(1, (System.currentTimeMillis() - start) / 1000);
        }

        boolean isExpired() {
            return System.currentTimeMillis() - last > 15000;
        }

        synchronized boolean markReported() {
            if (reported) {
                return false;
            }
            reported = true;
            return true;
        }
    }
}
