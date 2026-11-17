package com.smartmonitor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 配置
 * 用于调用 Python AI 微服务（Spring Boot 2.x 兼容写法）
 */
@Configuration
public class RestConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); // 连接超时 3 秒
        factory.setReadTimeout(5000);    // 读取超时 5 秒
        return new RestTemplate(factory);
    }
}
