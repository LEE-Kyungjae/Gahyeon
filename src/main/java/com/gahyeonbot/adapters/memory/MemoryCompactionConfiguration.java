package com.gahyeonbot.adapters.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** Dedicated capacity for optional memory maintenance; never shared with cognition or TTS. */
@Configuration
class MemoryCompactionConfiguration {

    @Bean(name = "memoryCompactionExecutor")
    ThreadPoolTaskExecutor memoryCompactionExecutor(
            @Value("${gahyeon.memory.compaction.queue-capacity:32}") int queueCapacity) {
        if (queueCapacity < 1 || queueCapacity > 10_000) {
            throw new IllegalArgumentException("memory compaction queue capacity must be between 1 and 10000");
        }
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("memory-compaction-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
