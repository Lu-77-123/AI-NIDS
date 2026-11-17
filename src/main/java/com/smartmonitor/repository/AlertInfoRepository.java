package com.smartmonitor.repository;

import com.smartmonitor.entity.AlertInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlertInfoRepository extends JpaRepository<AlertInfo, Long> {

    /** 查询最近告警（按时间倒序） */
    List<AlertInfo> findTop50ByOrderByAlertTimeDesc();

    /** 按设备查询告警 */
    List<AlertInfo> findByDeviceIdOrderByAlertTimeDesc(Long deviceId);

    /** 按状态查询 */
    List<AlertInfo> findByStatusOrderByAlertTimeDesc(Integer status);

    /** 查询时间范围内的告警 */
    List<AlertInfo> findByAlertTimeBetweenOrderByAlertTimeDesc(LocalDateTime start, LocalDateTime end);

    /** 统计今日告警数 */
    @Query("SELECT COUNT(a) FROM AlertInfo a WHERE a.alertTime >= :start")
    Long countAfter(@Param("start") LocalDateTime start);

    /** 统计各攻击类型数量 */
    @Query("SELECT a.attackType, COUNT(a) FROM AlertInfo a WHERE a.alertTime >= :start GROUP BY a.attackType")
    List<Object[]> countByAttackType(@Param("start") LocalDateTime start);
}
