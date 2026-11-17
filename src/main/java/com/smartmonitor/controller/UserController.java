package com.smartmonitor.controller;

import com.smartmonitor.entity.SysUser;
import com.smartmonitor.security.JwtUtil;
import com.smartmonitor.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户管理控制器
 * 提供用户 CRUD、登录、重置密码接口
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    public UserController(UserService userService, JwtUtil jwtUtil) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
    }

    /** 查询全部用户（列表中不含密码） */
    @GetMapping
    public List<SysUser> list() {
        List<SysUser> list = userService.listAll();
        list.forEach(u -> u.setPassword(null));
        return list;
    }

    /** 查询单个用户 */
    @GetMapping("/{id}")
    public SysUser get(@PathVariable Long id) {
        SysUser u = userService.getById(id);
        if (u != null) {
            u.setPassword(null);
        }
        return u;
    }

    /** 新增用户 */
    @PostMapping
    public ResponseEntity<?> add(@RequestBody SysUser user) {
        try {
            SysUser saved = userService.add(user);
            saved.setPassword(null);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        }
    }

    /** 修改用户 */
    @PutMapping
    public ResponseEntity<?> update(@RequestBody SysUser user) {
        try {
            SysUser saved = userService.update(user);
            saved.setPassword(null);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        }
    }

    /** 删除用户 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            userService.delete(id);
            return ResponseEntity.ok(successMap("删除成功"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(errorMap(e.getMessage()));
        }
    }

    /** 重置密码为默认 123456 */
    @PostMapping("/{id}/reset-password")
    public ResponseEntity<?> resetPassword(@PathVariable Long id) {
        boolean ok = userService.resetPassword(id);
        if (ok) {
            return ResponseEntity.ok(successMap("密码已重置为 123456"));
        }
        return ResponseEntity.badRequest().body(errorMap("用户不存在"));
    }

    /** 登录 */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        Map<String, Object> userInfo = userService.login(username, password);
        if (userInfo != null) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("user", userInfo);
            // 签发 JWT：前端后续请求需携带 Authorization: Bearer <token>
            Number id = (Number) userInfo.get("id");
            result.put("token", jwtUtil.generate(
                    id != null ? id.longValue() : null,
                    (String) userInfo.get("username"),
                    (String) userInfo.get("role")));
            result.put("message", "登录成功");
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.ok(errorMap("用户名或密码错误，或账号已被禁用"));
    }

    private Map<String, Object> errorMap(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("success", false);
        m.put("message", msg);
        return m;
    }

    private Map<String, Object> successMap(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("success", true);
        m.put("message", msg);
        return m;
    }
}
