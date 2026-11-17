package com.smartmonitor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 定时任务线程池配置。
 * Spring 默认只用 1 个线程调度所有 @Scheduled 方法，5 分钟一次的设备状态采集
 * 一旦遇到离线设备超时，就会阻塞 10 秒一次的流量采集与 WebSocket 推送。
 * 这里提供独立线程池，保证两类任务互不影响。
 */
@Configuration
public class ScheduleConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("monitor-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.initialize();
        taskRegistrar.setTaskScheduler(scheduler);
    }
}
