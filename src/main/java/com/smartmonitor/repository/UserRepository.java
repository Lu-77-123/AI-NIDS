package com.smartmonitor.repository;

import com.smartmonitor.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<SysUser, Long> {

    /** 根据用户名查询 */
    Optional<SysUser> findByUsername(String username);

    /** 是否存在指定用户名 */
    boolean existsByUsername(String username);
}
