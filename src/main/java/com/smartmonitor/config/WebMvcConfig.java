package com.smartmonitor.config;

import com.smartmonitor.security.JwtInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Web MVC 全局配置：
 * 1) 强制所有 HTTP 响应 JSON 使用 UTF-8 编码（避免 Content-Type: application/json 被客户端按 Latin-1 解析为乱码）
 * 2) 配置 StringHttpMessageConverter 默认字符集 UTF-8
 * 3) 允许跨域（本地 dev 下前端 8082 → 后端 8081）
 * 4) 注册 JWT 拦截器：/api/** 全部需要登录令牌，仅登录接口与 /error 放行
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    public WebMvcConfig(JwtInterceptor jwtInterceptor) {
        this.jwtInterceptor = jwtInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                // 白名单：登录接口（/ws/** WebSocket 不在 /api 下，天然不拦截）
                .excludePathPatterns("/api/user/login", "/error");
    }

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 1. String 消息：UTF-8
        StringHttpMessageConverter stringConverter = new StringHttpMessageConverter(StandardCharsets.UTF_8);
        stringConverter.setSupportedMediaTypes(Collections.singletonList(
                new MediaType("text", "plain", StandardCharsets.UTF_8)
        ));
        converters.add(0, stringConverter);

        // 2. JSON 消息：强制 Content-Type 携带 charset=utf-8
        for (int i = 0; i < converters.size(); i++) {
            HttpMessageConverter<?> c = converters.get(i);
            if (c instanceof MappingJackson2HttpMessageConverter) {
                MappingJackson2HttpMessageConverter jackson = (MappingJackson2HttpMessageConverter) c;
                jackson.setSupportedMediaTypes(Collections.singletonList(
                        new MediaType("application", "json", StandardCharsets.UTF_8)
                ));
                jackson.setDefaultCharset(StandardCharsets.UTF_8);
                break;
            }
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
