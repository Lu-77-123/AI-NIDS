package com.smartmonitor.repository;

import com.smartmonitor.entity.TrafficData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrafficDataRepository extends JpaRepository<TrafficData, Long> {

    /** 查询某设备最新一条流量记录 */
    TrafficData findFirstByDeviceIdOrderByCollectTimeDesc(Long deviceId);

    /** 取某设备最近 200 条（降序），由服务层按可配窗口切片，支持运行期调整窗口大小 */
    List<TrafficData> findTop200ByDeviceIdOrderByCollectTimeDesc(Long deviceId);

    /**
     * 查询某设备最近 30 条流量记录（子查询先按时间倒序取 30 条，外层再按时间升序，供趋势图绘制）。
     * 注意：不能用 findTop30...Asc 派生方法——其语义是“最早的 30 条”，设备记录超过 30 条后会永久冻结。
     */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT * FROM (SELECT * FROM traffic_data WHERE device_id = ?1 " +
                "ORDER BY collect_time DESC LIMIT 30) recent ORDER BY recent.collect_time ASC",
        nativeQuery = true
    )
    List<TrafficData> findRecent30ByDeviceIdOrderByCollectTimeAsc(Long deviceId);

    /** 查询所有现存设备的最新流量记录 */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT t.* FROM traffic_data t " +
                "INNER JOIN device d ON d.id = t.device_id " +
                "INNER JOIN (SELECT device_id, MAX(id) AS max_id FROM traffic_data GROUP BY device_id) m " +
                "ON t.id = m.max_id",
        nativeQuery = true
    )
    List<TrafficData> findLatestForAllDevices();

    /** 按时间聚合查询全网总流量（每个采集时间点的所有设备入/出流量之和） */
    @org.springframework.data.jpa.repository.Query(
        value = "SELECT DATE_FORMAT(t.collect_time, '%H:%i:%s') AS time_label, " +
                "SUM(t.in_bps) AS total_in, SUM(t.out_bps) AS total_out " +
                "FROM traffic_data t " +
                "INNER JOIN device d ON d.id = t.device_id " +
                "GROUP BY t.collect_time " +
                "ORDER BY t.collect_time ASC",
        nativeQuery = true
    )
    List<Object[]> findTotalTrafficByTime();
}
