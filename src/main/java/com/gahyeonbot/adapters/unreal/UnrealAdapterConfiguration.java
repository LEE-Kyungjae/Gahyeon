package com.gahyeonbot.adapters.unreal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.adapters.unreal.protocol.UnrealEventMapper;
import com.gahyeonbot.application.event.GahyeonEventQuery;
import com.gahyeonbot.application.identity.IdentityResolutionUseCase;
import com.gahyeonbot.application.conversation.ConversationStreamingUseCase;
import com.gahyeonbot.core.speech.SpeechSynthesisUseCase;
import com.gahyeonbot.core.world.WorldStateUseCase;
import com.gahyeonbot.application.behavior.WorldActionCoordinator;
import com.gahyeonbot.application.behavior.WorldActionPresentationPresence;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties({
        UnrealVisemeAlignmentProperties.class,
        UnrealRuntimeProperties.class
})
@ConditionalOnProperty(
        name = {"gahyeon.headless.enabled", "gahyeon.unreal.websocket.enabled"},
        havingValue = "true")
public class UnrealAdapterConfiguration {
    @Bean
    UnrealEventMapper unrealEventMapper() {
        return new UnrealEventMapper();
    }

    @Bean
    UnrealEventReplayService unrealEventReplayService(
            GahyeonEventQuery events,
            UnrealEventMapper mapper) {
        return new UnrealEventReplayService(events, mapper);
    }

    @Bean
    ThreadPoolTaskExecutor unrealCognitionExecutor(UnrealRuntimeProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("gahyeon-unreal-cognition-");
        executor.setCorePoolSize(properties.getCognitionCoreThreads());
        executor.setMaxPoolSize(properties.getCognitionMaxThreads());
        executor.setQueueCapacity(properties.getCognitionQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean
    ThreadPoolTaskExecutor unrealTtsExecutor(UnrealRuntimeProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("gahyeon-unreal-tts-");
        executor.setCorePoolSize(properties.getTtsThreads());
        executor.setMaxPoolSize(properties.getTtsThreads());
        executor.setQueueCapacity(properties.getTtsQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean
    @ConditionalOnProperty(name = "gahyeon.unreal.viseme.enabled", havingValue = "true")
    ThreadPoolTaskExecutor unrealVisemeAlignerExecutor(UnrealVisemeAlignmentProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("gahyeon-unreal-viseme-");
        executor.setCorePoolSize(properties.getThreads());
        executor.setMaxPoolSize(properties.getThreads());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean
    ThreadPoolTaskExecutor unrealOutboundExecutor(UnrealRuntimeProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("gahyeon-unreal-outbound-");
        executor.setCorePoolSize(properties.getOutboundThreads());
        executor.setMaxPoolSize(properties.getOutboundThreads());
        executor.setQueueCapacity(properties.getOutboundExecutorQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    @Bean
    UnrealEphemeralBroker unrealEphemeralBroker(
            @Qualifier("unrealOutboundExecutor") ThreadPoolTaskExecutor unrealOutboundExecutor,
            UnrealRuntimeProperties properties,
            UnrealRuntimeMetrics metrics) {
        var outbound = new UnrealEphemeralBroker(
                Clock.systemUTC(),
                unrealOutboundExecutor,
                properties.getOutboundPerRendererQueueCapacity(),
                metrics);
        metrics.bindOutbound(outbound);
        return outbound;
    }

    @Bean
    UnrealAudioCache unrealAudioCache(UnrealRuntimeMetrics metrics) {
        return new UnrealAudioCache(
                Clock.systemUTC(), Duration.ofMinutes(5),
                UnrealAudioCache.DEFAULT_MAX_ENTRIES,
                UnrealAudioCache.DEFAULT_MAX_ENTRY_BYTES,
                UnrealAudioCache.DEFAULT_MAX_TOTAL_BYTES,
                metrics);
    }

    @Bean
    UnrealSpeechPreparationPort unrealSpeechPreparationPort(
            SpeechSynthesisUseCase synthesis,
            UnrealAudioCache audio,
            UnrealEphemeralBroker outbound,
            @Qualifier("unrealTtsExecutor") ThreadPoolTaskExecutor unrealTtsExecutor,
            UnrealRuntimeMetrics metrics,
            UnrealVisemeTimelinePort visemes) {
        return new DefaultUnrealSpeechPreparationService(
                synthesis, audio, outbound, unrealTtsExecutor, metrics, visemes);
    }

    @Bean
    @ConditionalOnProperty(name = "gahyeon.unreal.viseme.enabled", havingValue = "true")
    UnrealVisemeTimelinePort exactUnrealVisemeTimelinePort(
            UnrealVisemeAlignmentProperties properties,
            @Qualifier("unrealVisemeAlignerExecutor") ThreadPoolTaskExecutor executor) {
        return new DeadlineUnrealVisemeTimelineProvider(
                new HttpUnrealVisemeTimelineProvider(properties),
                executor,
                Duration.ofMillis(properties.getPlaybackDeadlineMillis()));
    }

    @Bean
    @ConditionalOnMissingBean(UnrealVisemeTimelinePort.class)
    UnrealVisemeTimelinePort unrealVisemeTimelinePort() {
        return new PcmWavKoreanVisemeTimeline();
    }

    @Bean
    UnrealCommandDispatcher unrealCommandDispatcher(
            ConversationStreamingUseCase conversation,
            IdentityResolutionUseCase identities,
            @Qualifier("unrealCognitionExecutor") ThreadPoolTaskExecutor unrealCognitionExecutor,
            UnrealSpeechPreparationPort speech,
            UnrealRuntimeMetrics metrics,
            UnrealRuntimeProperties properties) {
        return new DefaultUnrealCommandDispatcher(
                conversation, identities, unrealCognitionExecutor, speech, metrics,
                properties.getSpeechSegmentMaxCharacters());
    }

    @Bean
    LatestUnrealPerceptionStore unrealPerceptionStore(UnrealRuntimeMetrics metrics) {
        var perception = new LatestUnrealPerceptionStore(Clock.systemUTC(), Duration.ofSeconds(10));
        metrics.bindPerceptionStore(perception);
        return perception;
    }

    @Bean
    UnrealPerceptionSessionTracker unrealPerceptionSessionTracker(UnrealRuntimeMetrics metrics) {
        var sessions = new UnrealPerceptionSessionTracker();
        metrics.bindPerceptionSessions(sessions);
        return sessions;
    }

    @Bean
    UnrealClientSessionRegistry unrealClientSessionRegistry(
            UnrealRuntimeMetrics metrics,
            UnrealRuntimeProperties properties) {
        var clients = new UnrealClientSessionRegistry(
                properties.getMaximumRendererConnections(),
                properties.getMaximumRendererConnectionsPerSession());
        metrics.bindClientSessions(clients);
        return clients;
    }

    @Bean
    WorldActionPresentationPresence unrealWorldActionPresentationPresence(
            UnrealClientSessionRegistry clients) {
        return worldId -> clients.hasRendererForWorld(worldId.value());
    }

    @Bean
    UnrealStreamingTranscriptAdmission unrealStreamingTranscriptAdmission(
            UnrealClientSessionRegistry clients,
            UnrealPerceptionSessionTracker perceptionSessions,
            LatestUnrealPerceptionStore perception,
            UnrealCommandDispatcher commands) {
        return new UnrealStreamingTranscriptAdmission(
                clients, perceptionSessions, perception, commands, Clock.systemUTC());
    }

    @Bean
    UnrealRuntimeMetrics unrealRuntimeMetrics(MeterRegistry meterRegistry) {
        return new UnrealRuntimeMetrics(meterRegistry);
    }

    @Bean
    UnrealWorldSnapshotProvider unrealWorldSnapshotProvider(WorldStateUseCase worlds) {
        return new DefaultUnrealWorldSnapshotProvider(worlds, Clock.systemUTC());
    }

    @Bean
    UnrealWebSocketHandler unrealWebSocketHandler(
            ObjectMapper objectMapper,
            UnrealEventReplayService replay,
            UnrealCommandDispatcher commands,
            LatestUnrealPerceptionStore perception,
            UnrealRuntimeMetrics metrics,
            UnrealEphemeralBroker outbound,
            UnrealPerceptionSessionTracker perceptionSessions,
            UnrealWorldSnapshotProvider worldSnapshots,
            WorldActionCoordinator worldActions,
            UnrealClientSessionRegistry clientSessions,
            UnrealRuntimeProperties properties) {
        return new UnrealWebSocketHandler(
                objectMapper, replay, commands, perception, metrics, outbound,
                perceptionSessions, worldSnapshots, worldActions::complete, clientSessions,
                Duration.ofSeconds(properties.getRendererHelloTimeoutSeconds()),
                Duration.ofSeconds(properties.getRendererHeartbeatTimeoutSeconds()));
    }
}
