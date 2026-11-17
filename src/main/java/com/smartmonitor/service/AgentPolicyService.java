package com.smartmonitor.service;

import com.smartmonitor.entity.AgentPolicy;
import com.smartmonitor.repository.AgentPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 智能体「小七」自动执行策略服务。
 *
 * 管理 5 类决策的自动执行开关，首次启动时写入默认策略：
 * 仅 ALERT_ESCALATE（告警升级）默认自动执行，其余四类需人工确认。
 */
@Service
public class AgentPolicyService {

    private static final Logger logger = LoggerFactory.getLogger(AgentPolicyService.class);

    /** 全部决策类型（固定顺序，供前端展示） */
    public static final List<String> ACTION_TYPES =
            Collections.unmodifiableList(Arrays.asList(
                    "ISOLATE", "DISCONNECT", "ADJUST_THRESHOLD", "ALERT_ESCALATE", "RETRAIN_MODEL"));

    /** 默认自动执行的类型 */
    private static final Set<String> DEFAULT_AUTO = new HashSet<>(Collections.singletonList("ALERT_ESCALATE"));

    private final AgentPolicyRepository policyRepository;

    public AgentPolicyService(AgentPolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    /** 启动时确保每种类型都有一条策略配置（JPA 自动建表后初始化） */
    @PostConstruct
    public void initDefaults() {
        try {
            for (String type : ACTION_TYPES) {
                if (!policyRepository.findByActionType(type).isPresent()) {
                    AgentPolicy p = new AgentPolicy();
                    p.setActionType(type);
                    p.setAutoExecute(DEFAULT_AUTO.contains(type));
                    p.setUpdatedTime(LocalDateTime.now());
                    p.setUpdatedBy("system");
                    policyRepository.save(p);
                }
            }
        } catch (Exception e) {
            // 表尚未就绪等情况下不阻断启动，查询时会再次惰性补齐
            logger.warn("小七策略初始化跳过: {}", e.getMessage());
        }
    }

    /** 返回全部策略（按固定顺序，缺失项即时补建） */
    public List<AgentPolicy> listPolicies() {
        List<AgentPolicy> result = new ArrayList<>();
        for (String type : ACTION_TYPES) {
            AgentPolicy p = policyRepository.findByActionType(type).orElseGet(() -> {
                AgentPolicy np = new AgentPolicy();
                np.setActionType(type);
                np.setAutoExecute(DEFAULT_AUTO.contains(type));
                np.setUpdatedTime(LocalDateTime.now());
                np.setUpdatedBy("system");
                return policyRepository.save(np);
            });
            result.add(p);
        }
        return result;
    }

    /** 该类型是否自动执行（策略缺失时走默认值） */
    public boolean isAutoExecute(String actionType) {
        return policyRepository.findByActionType(actionType)
                .map(AgentPolicy::getAutoExecute)
                .orElse(DEFAULT_AUTO.contains(actionType));
    }

    /**
     * 批量更新策略
     * @param updates 类型 → 是否自动执行
     * @param updatedBy 操作人
     */
    public List<AgentPolicy> updatePolicies(Map<String, Boolean> updates, String updatedBy) {
        List<AgentPolicy> result = new ArrayList<>();
        for (String type : ACTION_TYPES) {
            if (updates == null || !updates.containsKey(type)) continue;
            AgentPolicy p = policyRepository.findByActionType(type).orElseGet(() -> {
                AgentPolicy np = new AgentPolicy();
                np.setActionType(type);
                np.setAutoExecute(DEFAULT_AUTO.contains(type));
                return np;
            });
            p.setAutoExecute(Boolean.TRUE.equals(updates.get(type)));
            p.setUpdatedTime(LocalDateTime.now());
            p.setUpdatedBy(updatedBy != null ? updatedBy : "admin");
            result.add(policyRepository.save(p));
            logger.info("小七策略变更: {} 自动执行={} 操作人={}", type, p.getAutoExecute(), p.getUpdatedBy());
        }
        return result;
    }
}
