package com.smartmonitor.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 认证 / 鉴权拦截器。
 *
 * 认证：除白名单（在 WebMvcConfig 中配置：登录接口、/error）外，/api/** 全部需要
 *       Authorization: Bearer &lt;token&gt;，校验失败返回 401。
 * 鉴权：/api/user/** 用户管理接口仅 admin 角色可访问，其他角色返回 403。
 *
 * 说明：/ws/** WebSocket 握手路径不在 /api 下，不受本拦截器约束（实时大盘可匿名订阅）。
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(JwtInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final ObjectMapper mapper = new ObjectMapper();

    public JwtInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // CORS 预检请求直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String uri = request.getRequestURI();

        // 1. 认证：取 Authorization 头
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录已失效，请重新登录");
            return false;
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        JwtUtil.Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (JwtUtil.JwtAuthException e) {
            logger.info("JWT 校验失败 uri={}, reason={}", uri, e.getMessage());
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, e.getMessage());
            return false;
        }

        // 2. 鉴权：用户管理接口仅管理员
        if (uri.startsWith("/api/user/") || uri.equals("/api/user")) {
            if (!"admin".equals(claims.getRole())) {
                writeError(response, HttpServletResponse.SC_FORBIDDEN, "权限不足：该操作仅管理员可执行");
                return false;
            }
        }

        // 2.1 鉴权：AI 配置写操作（GET /api/ai/engine 所有登录用户可读）、告警删除仅管理员
        boolean aiConfigWrite = ("PUT".equalsIgnoreCase(request.getMethod()) && uri.equals("/api/ai/config"))
                || ("POST".equalsIgnoreCase(request.getMethod()) && uri.equals("/api/ai/config/reset"));
        boolean alertDelete = uri.startsWith("/api/alerts/") && "DELETE".equalsIgnoreCase(request.getMethod());
        if ((aiConfigWrite || alertDelete) && !"admin".equals(claims.getRole())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "权限不足：该操作仅管理员可执行");
            return false;
        }

        // 3. 声明透传给后续 Controller（如需获取当前用户可直接取 request attribute）
        request.setAttribute("currentUserId", claims.getUserId());
        request.setAttribute("currentUsername", claims.getUsername());
        request.setAttribute("currentUserRole", claims.getRole());
        return true;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("status", status);
        body.put("message", message);
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
