package com.gahyeonbot.adapters.unreal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.event.GahyeonEventQuery;
import com.gahyeonbot.application.identity.IdentityResolutionUseCase;
import com.gahyeonbot.application.conversation.ConversationStreamingUseCase;
import com.gahyeonbot.application.speech.StreamingTranscriptionPort;
import com.gahyeonbot.core.speech.SpeechSynthesisUseCase;
import com.gahyeonbot.core.speech.TranscriptionUseCase;
import com.gahyeonbot.core.world.WorldStateUseCase;
import com.gahyeonbot.application.behavior.WorldActionCoordinator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.websocket.server.ServerContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UnrealAdapterConfigurationTest {
    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withInitializer(applicationContext -> applicationContext.getServletContext()
                    .setAttribute(ServerContainer.class.getName(), mock(ServerContainer.class)))
            .withUserConfiguration(
                    UnrealAdapterConfiguration.class,
                    UnrealWebSocketConfiguration.class,
                    UnrealStreamingSttWebSocketConfiguration.class,
                    UnrealRuntimeHealthIndicator.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(GahyeonEventQuery.class, () -> (sequence, limit) -> List.of())
            .withBean(ConversationStreamingUseCase.class, () -> mock(ConversationStreamingUseCase.class))
            .withBean(SpeechSynthesisUseCase.class, () -> mock(SpeechSynthesisUseCase.class))
            .withBean(TranscriptionUseCase.class, () -> mock(TranscriptionUseCase.class))
            .withBean(IdentityResolutionUseCase.class, () -> mock(IdentityResolutionUseCase.class))
            .withBean(WorldStateUseCase.class, () -> mock(WorldStateUseCase.class))
            .withBean(WorldActionCoordinator.class, () -> mock(WorldActionCoordinator.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void staysDisabledByDefault() {
        context.run(result -> {
            assertThat(result).doesNotHaveBean(UnrealWebSocketHandler.class);
            assertThat(result).doesNotHaveBean(UnrealEventReplayService.class);
        });
    }

    @Test
    void requiresHeadlessAndUnrealFlagsTogether() {
        context.withPropertyValues("gahyeon.unreal.websocket.enabled=true")
                .run(result -> assertThat(result).doesNotHaveBean(UnrealWebSocketHandler.class));
        context.withPropertyValues(
                        "gahyeon.headless.enabled=true",
                        "gahyeon.unreal.websocket.enabled=true")
                .run(result -> {
                    assertThat(result).hasSingleBean(UnrealWebSocketHandler.class);
                    assertThat(result).hasSingleBean(UnrealEventReplayService.class);
                    assertThat(result).hasSingleBean(UnrealWorldSnapshotProvider.class);
                    assertThat(result).hasSingleBean(UnrealRuntimeHealthIndicator.class);
                    var container = result.getBean(
                            "&gahyeonUnrealWebSocketContainer",
                            ServletServerContainerFactoryBean.class);
                    assertThat(container.getMaxTextMessageBufferSize()).isEqualTo(65_536);
                    assertThat(container.getMaxBinaryMessageBufferSize()).isEqualTo(131_080);
                });
    }

    @Test
    void bindsBoundedRuntimePools() {
        context.withPropertyValues(
                        "gahyeon.headless.enabled=true",
                        "gahyeon.unreal.websocket.enabled=true",
                        "gahyeon.unreal.runtime.cognition-core-threads=2",
                        "gahyeon.unreal.runtime.cognition-max-threads=3",
                        "gahyeon.unreal.runtime.cognition-queue-capacity=7",
                        "gahyeon.unreal.runtime.tts-threads=2",
                        "gahyeon.unreal.runtime.tts-queue-capacity=5",
                        "gahyeon.unreal.runtime.outbound-threads=3",
                        "gahyeon.unreal.runtime.outbound-executor-queue-capacity=11",
                        "gahyeon.unreal.runtime.outbound-per-renderer-queue-capacity=13",
                        "gahyeon.unreal.runtime.maximum-renderer-connections=12",
                        "gahyeon.unreal.runtime.maximum-renderer-connections-per-session=3",
                        "gahyeon.unreal.runtime.renderer-hello-timeout-seconds=7",
                        "gahyeon.unreal.runtime.renderer-heartbeat-timeout-seconds=25")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    var cognition = result.getBean("unrealCognitionExecutor", ThreadPoolTaskExecutor.class);
                    var tts = result.getBean("unrealTtsExecutor", ThreadPoolTaskExecutor.class);
                    var outbound = result.getBean("unrealOutboundExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(cognition.getCorePoolSize()).isEqualTo(2);
                    assertThat(cognition.getMaxPoolSize()).isEqualTo(3);
                    assertThat(cognition.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(7);
                    assertThat(tts.getCorePoolSize()).isEqualTo(2);
                    assertThat(tts.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(5);
                    assertThat(outbound.getCorePoolSize()).isEqualTo(3);
                    assertThat(outbound.getThreadPoolExecutor().getQueue().remainingCapacity())
                            .isEqualTo(11);
                    assertThat(result.getBean(UnrealRuntimeProperties.class)
                            .getOutboundPerRendererQueueCapacity()).isEqualTo(13);
                    assertThat(result.getBean(UnrealRuntimeProperties.class)
                            .getMaximumRendererConnections()).isEqualTo(12);
                    assertThat(result.getBean(UnrealRuntimeProperties.class)
                            .getMaximumRendererConnectionsPerSession()).isEqualTo(3);
                    assertThat(result.getBean(UnrealRuntimeProperties.class)
                            .getRendererHelloTimeoutSeconds()).isEqualTo(7);
                    assertThat(result.getBean(UnrealRuntimeProperties.class)
                            .getRendererHeartbeatTimeoutSeconds()).isEqualTo(25);
                });
    }

    @Test
    void rejectsUnsafeRuntimeBounds() {
        context.withPropertyValues(
                        "gahyeon.headless.enabled=true",
                        "gahyeon.unreal.websocket.enabled=true",
                        "gahyeon.unreal.runtime.speech-segment-max-characters=0")
                .run(result -> assertThat(result).hasFailed());

        context.withPropertyValues(
                        "gahyeon.headless.enabled=true",
                        "gahyeon.unreal.websocket.enabled=true",
                        "gahyeon.unreal.runtime.renderer-heartbeat-timeout-seconds=14")
                .run(result -> assertThat(result).hasFailed());

        context.withPropertyValues(
                        "gahyeon.headless.enabled=true",
                        "gahyeon.unreal.websocket.enabled=true",
                        "gahyeon.unreal.runtime.renderer-hello-timeout-seconds=0")
                .run(result -> assertThat(result).hasFailed());

        context.withPropertyValues(
                        "gahyeon.headless.enabled=true",
                        "gahyeon.unreal.websocket.enabled=true",
                        "gahyeon.unreal.runtime.maximum-renderer-connections=2",
                        "gahyeon.unreal.runtime.maximum-renderer-connections-per-session=3")
                .run(result -> assertThat(result).hasFailed());

        context.withPropertyValues(
                        "gahyeon.headless.enabled=true",
                        "gahyeon.unreal.websocket.enabled=true",
                        "gahyeon.unreal.runtime.cognition-core-threads=4",
                        "gahyeon.unreal.runtime.cognition-max-threads=2")
                .run(result -> assertThat(result).hasFailed());
    }

    @Test
    void enablesExactAlignmentThroughItsOwnBoundedExecutor() {
        context.withPropertyValues(
                        "gahyeon.headless.enabled=true",
                        "gahyeon.unreal.websocket.enabled=true",
                        "gahyeon.unreal.viseme.enabled=true",
                        "gahyeon.unreal.viseme.endpoint=http://127.0.0.1:18768/align",
                        "gahyeon.unreal.viseme.playback-deadline-millis=175",
                        "gahyeon.unreal.viseme.threads=3",
                        "gahyeon.unreal.viseme.queue-capacity=6")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasSingleBean(UnrealVisemeTimelinePort.class);
                    assertThat(result.getBean(UnrealVisemeTimelinePort.class))
                            .isInstanceOf(DeadlineUnrealVisemeTimelineProvider.class);
                    var aligner = result.getBean(
                            "unrealVisemeAlignerExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(aligner.getCorePoolSize()).isEqualTo(3);
                    assertThat(aligner.getMaxPoolSize()).isEqualTo(3);
                    assertThat(aligner.getThreadPoolExecutor().getQueue().remainingCapacity())
                            .isEqualTo(6);
                    assertThat(result.getBean(UnrealVisemeAlignmentProperties.class)
                            .getPlaybackDeadlineMillis()).isEqualTo(175);
                });
    }

    @Test
    void rejectsUnsafeExactAlignmentBounds() {
        var enabled = context.withPropertyValues(
                "gahyeon.headless.enabled=true",
                "gahyeon.unreal.websocket.enabled=true",
                "gahyeon.unreal.viseme.enabled=true",
                "gahyeon.unreal.viseme.endpoint=http://127.0.0.1:18768/align");
        enabled.withPropertyValues("gahyeon.unreal.viseme.playback-deadline-millis=1001")
                .run(result -> assertThat(result).hasFailed());
        enabled.withPropertyValues("gahyeon.unreal.viseme.threads=0")
                .run(result -> assertThat(result).hasFailed());
        enabled.withPropertyValues("gahyeon.unreal.viseme.queue-capacity=65")
                .run(result -> assertThat(result).hasFailed());
        enabled.withPropertyValues("gahyeon.unreal.viseme.max-response-bytes=1023")
                .run(result -> assertThat(result).hasFailed());
    }

    @Test
    void streamingSttEndpointRequiresExplicitFlagAndProvider() {
        var enabled = context.withPropertyValues(
                "gahyeon.headless.enabled=true",
                "gahyeon.unreal.websocket.enabled=true",
                "gahyeon.unreal.streaming-stt.enabled=true");
        enabled.run(result -> assertThat(result)
                .doesNotHaveBean(UnrealStreamingSttWebSocketHandler.class));

        enabled.withBean(StreamingTranscriptionPort.class,
                        () -> mock(StreamingTranscriptionPort.class))
                .run(result -> assertThat(result)
                        .hasSingleBean(UnrealStreamingSttWebSocketHandler.class));
    }

    @Test
    void bindsStreamingSttToItsOwnBoundedOutboundPool() {
        context.withPropertyValues(
                        "gahyeon.headless.enabled=true",
                        "gahyeon.unreal.websocket.enabled=true",
                        "gahyeon.unreal.streaming-stt.enabled=true",
                        "gahyeon.unreal.streaming-stt.outbound-threads=3",
                        "gahyeon.unreal.streaming-stt.outbound-executor-queue-capacity=9",
                        "gahyeon.unreal.streaming-stt.outbound-per-connection-queue-capacity=17")
                .withBean(StreamingTranscriptionPort.class,
                        () -> mock(StreamingTranscriptionPort.class))
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    var outbound = result.getBean(
                            "unrealStreamingSttOutboundExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(outbound.getCorePoolSize()).isEqualTo(3);
                    assertThat(outbound.getMaxPoolSize()).isEqualTo(3);
                    assertThat(outbound.getThreadPoolExecutor().getQueue().remainingCapacity())
                            .isEqualTo(9);
                    assertThat(result.getBean(UnrealStreamingSttProperties.class)
                            .getOutboundPerConnectionQueueCapacity()).isEqualTo(17);
                });
    }

    @Test
    void rejectsUnsafeStreamingSttDeadline() {
        context.withPropertyValues(
                        "gahyeon.headless.enabled=true",
                        "gahyeon.unreal.websocket.enabled=true",
                        "gahyeon.unreal.streaming-stt.enabled=true",
                        "gahyeon.unreal.streaming-stt.maximum-stream-seconds=4")
                .withBean(StreamingTranscriptionPort.class,
                        () -> mock(StreamingTranscriptionPort.class))
                .run(result -> assertThat(result).hasFailed());

        context.withPropertyValues(
                        "gahyeon.headless.enabled=true",
                        "gahyeon.unreal.websocket.enabled=true",
                        "gahyeon.unreal.streaming-stt.enabled=true",
                        "gahyeon.unreal.streaming-stt.maximum-connections=0")
                .withBean(StreamingTranscriptionPort.class,
                        () -> mock(StreamingTranscriptionPort.class))
                .run(result -> assertThat(result).hasFailed());

        context.withPropertyValues(
                        "gahyeon.headless.enabled=true",
                        "gahyeon.unreal.websocket.enabled=true",
                        "gahyeon.unreal.streaming-stt.enabled=true",
                        "gahyeon.unreal.streaming-stt.initial-start-seconds=1")
                .withBean(StreamingTranscriptionPort.class,
                        () -> mock(StreamingTranscriptionPort.class))
                .run(result -> assertThat(result).hasFailed());

        context.withPropertyValues(
                        "gahyeon.headless.enabled=true",
                        "gahyeon.unreal.websocket.enabled=true",
                        "gahyeon.unreal.streaming-stt.enabled=true",
                        "gahyeon.unreal.streaming-stt.outbound-per-connection-queue-capacity=0")
                .withBean(StreamingTranscriptionPort.class,
                        () -> mock(StreamingTranscriptionPort.class))
                .run(result -> assertThat(result).hasFailed());
    }
}
