package com.smartmonitor.repository;

import com.smartmonitor.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    /** 根据 IP 查询设备（用于自动发现时去重） */
    Optional<Device> findByIp(String ip);
}
