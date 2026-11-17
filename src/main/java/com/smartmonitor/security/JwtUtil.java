package com.smartmonitor.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 轻量 JWT 工具（JWT 规范的 HS256 实现，仅依赖 JDK 原生 javax.crypto，无第三方库）。
 *
 * 结构：base64url(header) . base64url(payload) . base64url(HmacSHA256(header.payload, secret))
 * payload 声明：sub(用户名) / uid / role / iat(签发秒) / exp(过期秒)
 *
 * 配置项（application.yml，均可被同名环境变量覆盖）：
 *   app.jwt.secret        签名密钥，生产环境务必通过环境变量 JWT_SECRET 注入
 *   app.jwt.expire-hours  有效期小时数，默认 12
 */
@Component
public class JwtUtil {

    private static final Logger logger = LoggerFactory.getLogger(JwtUtil.class);
    private static final Base64.Encoder B64URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.jwt.secret:smart-monitor-jwt-secret-2026-change-me-in-production}")
    private String secret;

    @Value("${app.jwt.expire-hours:12}")
    private int expireHours;

    /** 登录成功后签发 token */
    public String generate(Long userId, String username, String role) {
        try {
            long now = System.currentTimeMillis() / 1000L;

            ObjectNode header = mapper.createObjectNode();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            ObjectNode payload = mapper.createObjectNode();
            payload.put("sub", username);
            payload.put("uid", userId);
            payload.put("role", role == null ? "viewer" : role);
            payload.put("iat", now);
            payload.put("exp", now + expireHours * 3600L);

            String h = B64URL_ENCODER.encodeToString(mapper.writeValueAsBytes(header));
            String p = B64URL_ENCODER.encodeToString(mapper.writeValueAsBytes(payload));
            String signingInput = h + "." + p;
            String sig = B64URL_ENCODER.encodeToString(hmacSha256(signingInput));
            return signingInput + "." + sig;
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签发失败: " + e.getMessage(), e);
        }
    }

    /**
     * 校验并解析 token。
     * @throws JwtAuthException 格式错误 / 签名不符 / 已过期
     */
    public Claims parse(String token) {
        if (token == null || token.isEmpty()) {
            throw new JwtAuthException("令牌为空");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new JwtAuthException("令牌格式错误");
        }
        // 1. 验签（常量时间比较，防时序侧信道）
        byte[] expected = hmacSha256(parts[0] + "." + parts[1]);
        byte[] actual;
        try {
            actual = B64URL_DECODER.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new JwtAuthException("令牌签名编码错误");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new JwtAuthException("令牌签名无效");
        }
        // 2. 解析 payload + 校验过期
        try {
            JsonNode payload = mapper.readTree(B64URL_DECODER.decode(parts[1]));
            long exp = payload.path("exp").asLong(0L);
            if (exp > 0 && System.currentTimeMillis() / 1000L > exp) {
                throw new JwtAuthException("令牌已过期");
            }
            Claims claims = new Claims();
            claims.userId = payload.path("uid").asLong();
            claims.username = payload.path("sub").asText("");
            claims.role = payload.path("role").asText("viewer");
            return claims;
        } catch (JwtAuthException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("JWT payload 解析失败: {}", e.getMessage());
            throw new JwtAuthException("令牌内容无法解析");
        }
    }

    private byte[] hmacSha256(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 计算失败", e);
        }
    }

    /** 解析后的令牌声明 */
    public static class Claims {
        private Long userId;
        private String username;
        private String role;

        public Long getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getRole() { return role; }
    }

    /** 令牌校验异常 */
    public static class JwtAuthException extends RuntimeException {
        public JwtAuthException(String message) { super(message); }
    }
}
