package com.smartmonitor.service.impl;

import com.smartmonitor.entity.DeviceInfo;
import com.smartmonitor.service.SnmpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TreeEvent;
import org.snmp4j.util.TreeUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SnmpServiceImpl implements SnmpService {

    private static final Logger logger = LoggerFactory.getLogger(SnmpServiceImpl.class);

    // ---------- 标准 MIB-II 接口表 OID ----------
    private static final String OID_SYS_DESCR   = "1.3.6.1.2.1.1.1.0";
    private static final String OID_SYS_NAME    = "1.3.6.1.2.1.1.5.0";
    private static final String OID_SYS_UPTIME  = "1.3.6.1.2.1.1.3.0";
    /** ifType 子树：6 = ethernet-csmacd（物理以太网口） */
    private static final String OID_IF_TYPE     = "1.3.6.1.2.1.2.2.1.3";
    /** ifOperStatus 子树：1 = up */
    private static final String OID_IF_OPER     = "1.3.6.1.2.1.2.2.1.8";
    private static final String OID_IF_IN_OCT   = "1.3.6.1.2.1.2.2.1.10";
    private static final String OID_IF_OUT_OCT  = "1.3.6.1.2.1.2.2.1.16";

    private static final int IF_TYPE_ETHERNET = 6;
    private static final int IF_OPER_UP = 1;

    @Value("${snmp.timeout:2000}")
    private long timeout;

    @Value("${snmp.retries:2}")
    private int retries;

    /**
     * 每台设备选定的监控接口 ifIndex 缓存。
     * 真实设备（华为 AR/S 系列）物理口 ifIndex 通常不是 1（GE0/0/0 常为 5），
     * 首次采集时通过 ifTable 选出"UP 的物理以太网口"，之后直接 GET，避免每轮 walk。
     */
    private final Map<String, Integer> preferredIfIndex = new ConcurrentHashMap<>();

    @Override
    public DeviceInfo collectDeviceInfo(String ip, String community) {
        DeviceInfo info = new DeviceInfo();

        try (Snmp snmp = createSnmpSession()) {
            snmp.listen();
            CommunityTarget target = buildTarget(ip, community, timeout);

            // 采集设备名称
            ResponseEvent nameEvent = snmp.send(buildGetPDU(OID_SYS_NAME), target);
            if (nameEvent != null && nameEvent.getResponse() != null) {
                info.setName(getResponseValue(nameEvent));
            }

            // 采集运行时间（百分之一秒）
            ResponseEvent uptimeEvent = snmp.send(buildGetPDU(OID_SYS_UPTIME), target);
            if (uptimeEvent != null && uptimeEvent.getResponse() != null) {
                String upTimeTicks = getResponseValue(uptimeEvent);
                if (upTimeTicks != null && !upTimeTicks.contains("noSuchObject")) {
                    try {
                        long ticks = Long.parseLong(upTimeTicks.trim());
                        info.setUpTime(formatUpTime(ticks));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            // 只要设备名称能正常取到就视为在线
            info.setStatus(info.getName() != null && !info.getName().isEmpty() ? "在线" : "离线");

        } catch (Exception e) {
            logger.warn("SNMP 采集设备信息失败，IP: {}, 错误: {}", ip, e.getMessage());
            info.setStatus("离线");
        }
        return info;
    }

    /**
     * 通过 SNMP 采集接口流量计数器。
     *
     * 真实设备兼容：不再写死 ifIndex=1（模拟器习惯）。首次采集时 WALK ifTable，
     * 选择 ifType=6(以太网) 且 ifOperStatus=up(1) 的物理口——多口满足时取累计字节最大者，
     * 全为 0（刚上线）时取最大 ifIndex（通常即主上联物理口），选定后按 IP 缓存。
     */
    @Override
    public long[] collectTraffic(String ip, String community) {
        try (Snmp snmp = createSnmpSession()) {
            snmp.listen();
            CommunityTarget target = buildTarget(ip, community, timeout);

            Integer idx = preferredIfIndex.get(ip);
            if (idx == null) {
                long[] viaWalk = selectUplinkByWalk(snmp, target, ip);
                if (viaWalk != null) {
                    return viaWalk;
                }
                idx = 1; // walk 失败的兜底：保持对模拟器/简单代理的兼容
            }

            // 已选定接口：一个 GET 同时取入/出计数器
            PDU pdu = buildGetPDU(OID_IF_IN_OCT + "." + idx, OID_IF_OUT_OCT + "." + idx);
            ResponseEvent event = snmp.send(pdu, target);
            if (event == null || event.getResponse() == null) {
                return null;
            }
            long inOctets = 0, outOctets = 0;
            for (int i = 0; i < event.getResponse().size(); i++) {
                VariableBinding vb = event.getResponse().get(i);
                if (!isExceptionVariable(vb)) {
                    long v = vb.getVariable().toLong();
                    if (vb.getOid().toString().startsWith(OID_IF_IN_OCT)) {
                        inOctets = v;
                    } else if (vb.getOid().toString().startsWith(OID_IF_OUT_OCT)) {
                        outOctets = v;
                    }
                }
            }
            return new long[]{inOctets, outOctets};

        } catch (Exception e) {
            logger.warn("SNMP 采集流量失败，IP: {}, 错误: {}", ip, e.getMessage());
            return null;
        }
    }

    @Override
    public String[] probeDevice(String ip, String community, int timeoutMs) {
        try (Snmp snmp = createSnmpSession()) {
            snmp.listen();
            CommunityTarget target = buildTarget(ip, community, timeoutMs);

            String sysName = null;
            String sysDescr = null;

            ResponseEvent nameEvent = snmp.send(buildGetPDU(OID_SYS_NAME), target);
            if (nameEvent != null && nameEvent.getResponse() != null) {
                sysName = getResponseValue(nameEvent);
            }

            if (sysName != null && !sysName.isEmpty()) {
                ResponseEvent descrEvent = snmp.send(buildGetPDU(OID_SYS_DESCR), target);
                if (descrEvent != null && descrEvent.getResponse() != null) {
                    sysDescr = getResponseValue(descrEvent);
                }
            }

            if (sysName != null && !sysName.isEmpty()) {
                return new String[]{sysName, sysDescr != null ? sysDescr : ""};
            }
            return null;

        } catch (Exception e) {
            return null;
        }
    }

    // ======================== 内部方法 ========================

    /**
     * WALK ifTable（type / operStatus / inOctets / outOctets）选出被监控的物理上联口。
     * 仅在该设备首次采集时执行；选定后写缓存。
     *
     * @return 选中接口的 [inOctets, outOctets]；设备不可达或没有任何 UP 以太网口时返回 null
     */
    private long[] selectUplinkByWalk(Snmp snmp, CommunityTarget target, String ip) {
        Map<Integer, Integer> types = walkColumn(snmp, target, OID_IF_TYPE);
        Map<Integer, Integer> opers = walkColumn(snmp, target, OID_IF_OPER);
        Map<Integer, Long> inMap = walkCounterColumn(snmp, target, OID_IF_IN_OCT);
        Map<Integer, Long> outMap = walkCounterColumn(snmp, target, OID_IF_OUT_OCT);
        if (types == null || opers == null || inMap == null || outMap == null) {
            return null; // walk 失败（含设备不可达）
        }

        // 候选：物理以太网口且处于 up
        List<Integer> candidates = new ArrayList<>();
        for (Integer ifIndex : inMap.keySet()) {
            if (IF_TYPE_ETHERNET == types.getOrDefault(ifIndex, -1)
                    && IF_OPER_UP == opers.getOrDefault(ifIndex, -1)) {
                candidates.add(ifIndex);
            }
        }
        if (candidates.isEmpty()) {
            logger.warn("设备 {} 没有处于 up 状态的物理以太网口，本轮跳过流量采集", ip);
            return null;
        }

        // 多口候选：累计字节最大者优先；全为 0（设备刚启动）时取最大 ifIndex
        Integer chosen = candidates.get(0);
        long bestBytes = inMap.getOrDefault(chosen, 0L) + outMap.getOrDefault(chosen, 0L);
        for (Integer cand : candidates) {
            long bytes = inMap.getOrDefault(cand, 0L) + outMap.getOrDefault(cand, 0L);
            logger.info("设备 {} 候选接口 ifIndex={}, inOctets={}, outOctets={}, 合计={}",
                    ip, cand, inMap.getOrDefault(cand, 0L), outMap.getOrDefault(cand, 0L), bytes);
            if (bytes > bestBytes || (bytes == bestBytes && cand > chosen)) {
                bestBytes = bytes;
                chosen = cand;
            }
        }

        preferredIfIndex.put(ip, chosen);
        logger.info("设备 {} 选定监控接口 ifIndex={}（候选 up 以太网口: {}）", ip, chosen, candidates);
        return new long[]{inMap.getOrDefault(chosen, 0L), outMap.getOrDefault(chosen, 0L)};
    }

    /** WALK 整型列（ifType / ifOperStatus），返回 ifIndex -> 值；walk 出错返回 null */
    private Map<Integer, Integer> walkColumn(Snmp snmp, CommunityTarget target, String oid) {
        List<TreeEvent> events = new TreeUtils(snmp, new DefaultPDUFactory()).getSubtree(target, new OID(oid));
        if (events == null) return null;
        Map<Integer, Integer> result = new HashMap<>();
        for (TreeEvent event : events) {
            if (event == null || event.getVariableBindings() == null) return null;
            for (VariableBinding vb : event.getVariableBindings()) {
                if (!isExceptionVariable(vb)) {
                    result.put(indexSuffix(vb.getOid(), oid), vb.getVariable().toInt());
                }
            }
        }
        return result;
    }

    /** WALK 计数器列（ifInOctets / ifOutOctets，Counter32/64 均可），返回 ifIndex -> 值；walk 出错返回 null */
    private Map<Integer, Long> walkCounterColumn(Snmp snmp, CommunityTarget target, String oid) {
        List<TreeEvent> events = new TreeUtils(snmp, new DefaultPDUFactory()).getSubtree(target, new OID(oid));
        if (events == null) return null;
        Map<Integer, Long> result = new HashMap<>();
        for (TreeEvent event : events) {
            if (event == null || event.getVariableBindings() == null) return null;
            for (VariableBinding vb : event.getVariableBindings()) {
                if (!isExceptionVariable(vb)) {
                    result.put(indexSuffix(vb.getOid(), oid), vb.getVariable().toLong());
                }
            }
        }
        return result;
    }

    /** 从完整 OID 中取列前缀后的最后一段（ifIndex，真实设备单段索引场景） */
    private int indexSuffix(OID fullOid, String prefix) {
        OID prefixOid = new OID(prefix);
        if (fullOid.startsWith(prefixOid)) {
            return fullOid.get(fullOid.size() - 1);
        }
        return fullOid.last();
    }

    private Snmp createSnmpSession() throws Exception {
        DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping();
        return new Snmp(transport);
    }

    private CommunityTarget buildTarget(String ip, String community, long timeoutMs) {
        Address address = new UdpAddress(ip + "/161");
        CommunityTarget target = new CommunityTarget();
        target.setAddress(address);
        target.setCommunity(new OctetString(community));
        target.setVersion(SnmpConstants.version2c);
        target.setTimeout(timeoutMs);
        target.setRetries(retries);
        return target;
    }

    private PDU buildGetPDU(String... oids) {
        PDU pdu = new PDU();
        pdu.setType(PDU.GET);
        for (String oid : oids) {
            pdu.add(new VariableBinding(new OID(oid)));
        }
        return pdu;
    }

    private String getResponseValue(ResponseEvent event) {
        if (event.getResponse() != null && event.getResponse().getErrorStatus() == PDU.noError) {
            if (!event.getResponse().getVariableBindings().isEmpty()) {
                VariableBinding vb = event.getResponse().get(0);
                if (!isExceptionVariable(vb)) {
                    return vb.getVariable().toString();
                }
            }
        }
        return null;
    }

    /** 判断响应变量是否为 noSuchObject / noSuchInstance / endOfMibView 异常值 */
    private boolean isExceptionVariable(VariableBinding vb) {
        if (vb == null || vb.getVariable() == null) {
            return true;
        }
        String syntax = vb.getVariable().getSyntaxString();
        return "noSuchObject".equals(syntax) || "noSuchInstance".equals(syntax) || "endOfMibView".equals(syntax);
    }

    /**
     * 将 SNMP 时间戳（百分之一秒）转换为可读格式。
     * 示例输入: 70904586 -> 输出: 8天 4小时 57分
     */
    private String formatUpTime(long ticks) {
        long totalSeconds = ticks / 100;
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("天 ");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append("小时 ");
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            sb.append(minutes).append("分 ");
        }
        sb.append(seconds).append("秒");
        return sb.toString();
    }
}
