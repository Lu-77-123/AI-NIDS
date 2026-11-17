package com.smartmonitor.repository;

import com.smartmonitor.entity.AiConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiConfigRepository extends JpaRepository<AiConfig, Long> {
    AiConfig findFirstByOrderByIdAsc();
}
