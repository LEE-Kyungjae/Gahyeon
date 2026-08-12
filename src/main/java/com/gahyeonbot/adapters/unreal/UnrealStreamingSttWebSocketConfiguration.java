package com.gahyeonbot.adapters.unreal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.speech.StreamingTranscriptionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(UnrealStreamingSttProperties.class)
@ConditionalOnBean(StreamingTranscriptionPort.class)
@ConditionalOnProperty(
        name = {"gahyeon.headless.enabled", "gahyeon.unreal.websocket.enabled",
                "gahyeon.unreal.streaming-stt.enabled"},
        havingValue = "true")
public class UnrealStreamingSttWebSocketConfiguration implements WebSocketConfigurer {
    private final UnrealStreamingSttWebSocketHandler handler;

    public UnrealStreamingSttWebSocketConfiguration(UnrealStreamingSttWebSocketHandler handler) {
        this.handler = handler;
    }

    @Bean
    static UnrealStreamingSttWebSocketHandler unrealStreamingSttWebSocketHandler(
            ObjectMapper objectMapper,
            StreamingTranscriptionPort provider,
            UnrealStreamingTranscriptAdmission admission,
            UnrealRuntimeMetrics metrics,
            ScheduledExecutorService unrealStreamingSttDeadlineScheduler,
            @Qualifier("unrealStreamingSttOutboundExecutor") ThreadPoolTaskExecutor outboundExecutor,
            UnrealStreamingSttProperties properties) {
        return new UnrealStreamingSttWebSocketHandler(
                objectMapper, provider, admission, metrics, System::nanoTime,
                unrealStreamingSttDeadlineScheduler, properties.getMaximumStreamSeconds(),
                properties.getMaximumConnections(), properties.getInitialStartSeconds(),
                outboundExecutor, properties.getOutboundPerConnectionQueueCapacity());
    }

    @Bean
    static ThreadPoolTaskExecutor unrealStreamingSttOutboundExecutor(
            UnrealStreamingSttProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("gahyeon-unreal-streaming-stt-outbound-");
        executor.setCorePoolSize(properties.getOutboundThreads());
        executor.setMaxPoolSize(properties.getOutboundThreads());
        executor.setQueueCapacity(properties.getOutboundExecutorQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean(destroyMethod = "shutdownNow")
    static ScheduledExecutorService unrealStreamingSttDeadlineScheduler() {
        var scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "gahyeon-unreal-streaming-stt-deadline");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/gahyeon/unreal/stt/v1");
    }
}
