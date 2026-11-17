package com.smartmonitor.repository;

import com.smartmonitor.entity.AgentPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentPolicyRepository extends JpaRepository<AgentPolicy, Long> {

    Optional<AgentPolicy> findByActionType(String actionType);

    List<AgentPolicy> findAllByOrderByIdAsc();
}
