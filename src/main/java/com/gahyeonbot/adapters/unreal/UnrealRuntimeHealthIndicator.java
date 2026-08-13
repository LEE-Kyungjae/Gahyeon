package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.speech.StreamingTranscriptionPort;
import com.gahyeonbot.core.speech.SpeechSynthesisUseCase;
import com.gahyeonbot.core.speech.TranscriptionUseCase;
import com.gahyeonbot.core.speech.VoiceProfileId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Readiness for the voice dependencies required by the Unreal realtime adapter. */
@Component
@ConditionalOnProperty(
        name = {"gahyeon.headless.enabled", "gahyeon.unreal.websocket.enabled"},
        havingValue = "true")
public final class UnrealRuntimeHealthIndicator implements HealthIndicator {
    private final SpeechSynthesisUseCase synthesis;
    private final TranscriptionUseCase transcription;
    private final ObjectProvider<StreamingTranscriptionPort> streamingProvider;
    private final boolean streamingEnabled;

    public UnrealRuntimeHealthIndicator(
            SpeechSynthesisUseCase synthesis,
            TranscriptionUseCase transcription,
            ObjectProvider<StreamingTranscriptionPort> streamingProvider,
            @Value("${gahyeon.unreal.streaming-stt.enabled:false}") boolean streamingEnabled) {
        this.synthesis = synthesis;
        this.transcription = transcription;
        this.streamingProvider = streamingProvider;
        this.streamingEnabled = streamingEnabled;
    }

    @Override
    public Health health() {
        boolean ttsReady = ready(() -> synthesis.isReady(VoiceProfileId.ASSISTANT));
        boolean batchSttReady = ready(transcription::isReady);
        StreamingTranscriptionPort streaming = streamingProvider.getIfAvailable();
        boolean streamingProviderPresent = streaming != null;
        boolean streamingReady = !streamingEnabled
                || streamingProviderPresent && ready(streaming::isReady);
        List<String> unavailable = new ArrayList<>();
        if (!ttsReady) unavailable.add("tts");
        if (!batchSttReady) unavailable.add("batch_stt");
        if (!streamingReady) unavailable.add(
                streamingProviderPresent ? "streaming_stt" : "streaming_stt_provider_missing");

        Health.Builder result = unavailable.isEmpty() ? Health.up() : Health.down();
        return result
                .withDetail("ttsReady", ttsReady)
                .withDetail("batchSttReady", batchSttReady)
                .withDetail("streamingSttEnabled", streamingEnabled)
                .withDetail("streamingSttProviderPresent", streamingProviderPresent)
                .withDetail("streamingSttReady", streamingReady)
                .withDetail("unavailable", unavailable)
                .build();
    }

    private static boolean ready(ReadinessProbe probe) {
        try {
            return probe.ready();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @FunctionalInterface
    private interface ReadinessProbe {
        boolean ready();
    }
}
