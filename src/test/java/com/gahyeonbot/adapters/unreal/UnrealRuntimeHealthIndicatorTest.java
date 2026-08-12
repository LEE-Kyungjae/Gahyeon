package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.speech.StreamingTranscriptionPort;
import com.gahyeonbot.core.speech.SpeechSynthesisUseCase;
import com.gahyeonbot.core.speech.TranscriptionUseCase;
import com.gahyeonbot.core.speech.VoiceProfileId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UnrealRuntimeHealthIndicatorTest {
    @Test
    void reportsReadyWhenRequiredVoiceProvidersAreReadyAndStreamingIsDisabled() {
        var synthesis = mock(SpeechSynthesisUseCase.class);
        var transcription = mock(TranscriptionUseCase.class);
        when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
        when(transcription.isReady()).thenReturn(true);

        var health = new UnrealRuntimeHealthIndicator(
                synthesis, transcription, provider(null), false).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("streamingSttReady", true);
    }

    @Test
    void reportsMissingStreamingProviderWhenExplicitlyEnabled() {
        var synthesis = mock(SpeechSynthesisUseCase.class);
        var transcription = mock(TranscriptionUseCase.class);
        when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
        when(transcription.isReady()).thenReturn(true);

        var health = new UnrealRuntimeHealthIndicator(
                synthesis, transcription, provider(null), true).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("streamingSttProviderPresent", false);
        assertThat(health.getDetails().get("unavailable"))
                .isEqualTo(java.util.List.of("streaming_stt_provider_missing"));
    }

    @Test
    void providerProbeFailureIsReportedAsDownInsteadOfEscapingHealthEndpoint() {
        var synthesis = mock(SpeechSynthesisUseCase.class);
        var transcription = mock(TranscriptionUseCase.class);
        var streaming = mock(StreamingTranscriptionPort.class);
        when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenThrow(new IllegalStateException());
        when(transcription.isReady()).thenReturn(false);
        when(streaming.isReady()).thenThrow(new IllegalStateException());

        var health = new UnrealRuntimeHealthIndicator(
                synthesis, transcription, provider(streaming), true).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails().get("unavailable"))
                .isEqualTo(java.util.List.of("tts", "batch_stt", "streaming_stt"));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StreamingTranscriptionPort> provider(
            StreamingTranscriptionPort value) {
        ObjectProvider<StreamingTranscriptionPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
