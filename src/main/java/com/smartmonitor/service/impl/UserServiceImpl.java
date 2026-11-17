package com.smartmonitor.service.impl;

import com.smartmonitor.entity.SysUser;
import com.smartmonitor.repository.UserRepository;
import com.smartmonitor.service.UserService;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_PASSWORD = "123456";

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public List<SysUser> listAll() {
        return userRepository.findAll();
    }

    @Override
    public SysUser getById(Long id) {
        return userRepository.findById(id).orElse(null);
    }

    @Override
    public SysUser add(SysUser user) {
        // 用户名唯一性校验
        if (userRepository.existsByUsername(user.getUsername())) {
            throw new RuntimeException("用户名已存在");
        }
        // 默认角色 / 状态
        if (user.getRole() == null || user.getRole().isEmpty()) {
            user.setRole("viewer");
        }
        if (user.getStatus() == null) {
            user.setStatus(1);
        }
        // 密码 MD5 加密，为空则用默认密码
        String pwd = (user.getPassword() == null || user.getPassword().isEmpty())
                ? DEFAULT_PASSWORD : user.getPassword();
        user.setPassword(md5(pwd));
        return userRepository.save(user);
    }

    @Override
    public SysUser update(SysUser user) {
        SysUser existing = userRepository.findById(user.getId())
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        existing.setRealName(user.getRealName());
        existing.setRole(user.getRole());
        existing.setEmail(user.getEmail());
        existing.setPhone(user.getPhone());
        existing.setStatus(user.getStatus());
        // 密码有值才更新（前端编辑时可选填）
        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            existing.setPassword(md5(user.getPassword()));
        }
        return userRepository.save(existing);
    }

    @Override
    public void delete(Long id) {
        if (!userRepository.existsById(id)) {
            throw new RuntimeException("用户不存在");
        }
        userRepository.deleteById(id);
    }

    @Override
    public Map<String, Object> login(String username, String password) {
        SysUser user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return null;
        }
        if (!md5(password).equals(user.getPassword())) {
            return null;
        }
        if (user.getStatus() == null || user.getStatus() == 0) {
            return null; // 账号已禁用
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("realName", user.getRealName());
        result.put("role", user.getRole());
        result.put("email", user.getEmail());
        result.put("phone", user.getPhone());
        return result;
    }

    @Override
    public boolean resetPassword(Long id) {
        SysUser user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return false;
        }
        user.setPassword(md5(DEFAULT_PASSWORD));
        userRepository.save(user);
        return true;
    }

    /** MD5 加密 */
    private String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            BigInteger number = new BigInteger(1, digest);
            StringBuilder hashText = new StringBuilder(number.toString(16));
            while (hashText.length() < 32) {
                hashText.insert(0, "0");
            }
            return hashText.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 加密失败", e);
        }
    }
}
