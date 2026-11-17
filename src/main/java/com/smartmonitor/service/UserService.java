package com.smartmonitor.service;

import com.smartmonitor.entity.SysUser;

import java.util.List;
import java.util.Map;

public interface UserService {

    /** 查询全部用户 */
    List<SysUser> listAll();

    /** 根据 ID 查询用户 */
    SysUser getById(Long id);

    /** 新增用户（密码自动 MD5 加密） */
    SysUser add(SysUser user);

    /** 修改用户（密码为空则不修改密码） */
    SysUser update(SysUser user);

    /** 删除用户 */
    void delete(Long id);

    /**
     * 用户登录
     * @return 成功返回用户信息 Map（不含密码），失败返回 null
     */
    Map<String, Object> login(String username, String password);

    /** 重置密码为默认值 123456 */
    boolean resetPassword(Long id);
}
