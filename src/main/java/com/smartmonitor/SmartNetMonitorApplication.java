package com.smartmonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmartNetMonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SmartNetMonitorApplication.class, args);
        System.out.println("🚀 局域网监控系统启动成功！");
    }
}
