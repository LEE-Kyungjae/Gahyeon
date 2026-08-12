package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.speech.AudioOutput;
import com.gahyeonbot.core.speech.TranscriptionUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnrealSpeechControllerTest {
    @Test
    void servesCachedAudioWithoutAllowingStorageCaching() {
        var cache = new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5));
        String id = cache.put(new AudioOutput(new byte[]{1, 2, 3}, "audio/wav", "wav"));
        var controller = new UnrealSpeechController(
                cache, mock(TranscriptionUseCase.class),
                new UnrealRuntimeMetrics(new SimpleMeterRegistry()));

        var response = controller.audio(id);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody()).containsExactly(1, 2, 3);
        assertThat(controller.audio("missing").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void routesWavThroughCoreTranscriptionPort() {
        var transcription = mock(TranscriptionUseCase.class);
        var registry = new SimpleMeterRegistry();
        when(transcription.isReady()).thenReturn(true);
        when(transcription.transcribe(any())).thenReturn("가현아, 안녕.");
        var controller = new UnrealSpeechController(
                new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5)), transcription,
                new UnrealRuntimeMetrics(registry));

        var response = controller.transcribe(new byte[]{1, 2, 3, 4});

        assertThat(response.transcript()).isEqualTo("가현아, 안녕.");
        verify(transcription).transcribe(any());
        assertThat(registry.get("gahyeon.unreal.stt.request")
                .tag("result", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void rejectsAudioWhenTranscriptionIsUnavailableOrEmpty() {
        var transcription = mock(TranscriptionUseCase.class);
        var controller = new UnrealSpeechController(
                new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5)), transcription,
                new UnrealRuntimeMetrics(new SimpleMeterRegistry()));

        assertThat(controller.status().transcriptionReady()).isFalse();
        assertThatThrownBy(() -> controller.transcribe(new byte[]{1}))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(503);

        when(transcription.isReady()).thenReturn(true);
        assertThatThrownBy(() -> controller.transcribe(new byte[0]))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
                .isEqualTo(413);
    }
}
