package com.smartmonitor.repository;

import com.smartmonitor.entity.AgentAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentActionRepository extends JpaRepository<AgentAction, Long> {

    /** 按状态查询决策记录 */
    List<AgentAction> findByStatusOrderByActionTimeDesc(Integer status);

    /** 查询全部决策记录（最新在前） */
    List<AgentAction> findAllByOrderByActionTimeDesc();

    /** 查询某设备近期未处理的决策 */
    List<AgentAction> findByDeviceIdAndStatusOrderByActionTimeDesc(Long deviceId, Integer status);

    /** 查询某时间段内的决策数 */
    long countByActionTimeAfter(LocalDateTime time);

    /** 查询某设备某类型决策的近期记录数 */
    long countByDeviceIdAndActionTypeAndActionTimeAfter(Long deviceId, String actionType, LocalDateTime time);

    /** 统计某类型决策在指定状态下的数量（用于驳回反哺：status=2） */
    long countByActionTypeAndStatus(String actionType, Integer status);
}
