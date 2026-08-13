package com.gahyeonbot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Shared bounded scheduler used by Core and adapter maintenance jobs. */
@Configuration
public class TaskSchedulerConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(TaskSchedulerConfiguration.class);

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scheduler-");
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setErrorHandler(t -> logger.error("스케줄 작업 실행 중 오류", t));
        return scheduler;
    }
}
