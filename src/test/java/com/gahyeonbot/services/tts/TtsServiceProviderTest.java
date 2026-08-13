package com.gahyeonbot.services.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TtsServiceProviderTest {
    @Test
    void usesSelectedCustomProvider() throws Exception {
        TtsProperties properties = new TtsProperties();
        properties.setProvider("custom");
        Path customAudio = Path.of("custom.wav");
        TtsService service = new TtsService(properties, new ObjectMapper(), List.of(
                provider("custom", true, customAudio, null),
                provider("edge", true, Path.of("edge.mp3"), null)));

        assertThat(service.synthesizeSegmentToAudio("안녕")).isEqualTo(customAudio);
    }

    @Test
    void fallsBackToEdgeWhenCustomFails() throws Exception {
        TtsProperties properties = new TtsProperties();
        properties.setProvider("custom");
        properties.setFallbackToEdge(true);
        Path edgeAudio = Path.of("edge.mp3");
        TtsService service = new TtsService(properties, new ObjectMapper(), List.of(
                provider("custom", true, null, new IllegalStateException("offline")),
                provider("edge", true, edgeAudio, null)));

        assertThat(service.synthesizeSegmentToAudio("안녕")).isEqualTo(edgeAudio);
    }

    @Test
    void interruptionNeverFallsBackToAnotherVoiceProvider() {
        TtsProperties properties = new TtsProperties();
        properties.setProvider("custom");
        properties.setFallbackToEdge(true);
        var edgeCalls = new java.util.concurrent.atomic.AtomicInteger();
        TtsProvider interrupted = provider(
                "custom", true, null, new InterruptedException("barge-in"));
        TtsProvider edge = new TtsProvider() {
            @Override public String name() { return "edge"; }
            @Override public boolean isReady() { return true; }
            @Override public Path synthesize(String text) {
                edgeCalls.incrementAndGet();
                return Path.of("edge.mp3");
            }
        };
        TtsService service = new TtsService(
                properties, new ObjectMapper(), List.of(interrupted, edge));

        assertThatThrownBy(() -> service.synthesizeSegmentToAudio("취소할 문장"))
                .isInstanceOf(InterruptedException.class);
        assertThat(edgeCalls).hasValue(0);
    }

    @Test
    void approvedVoiceIdentityMismatchNeverFallsBackToEdge() {
        TtsProperties properties = new TtsProperties();
        properties.setProvider("custom");
        properties.setFallbackToEdge(true);
        var edgeCalls = new java.util.concurrent.atomic.AtomicInteger();
        TtsProvider custom = provider("custom", true, null,
                new TtsIdentityMismatchException("wrong release"));
        TtsProvider edge = new TtsProvider() {
            @Override public String name() { return "edge"; }
            @Override public boolean isReady() { return true; }
            @Override public Path synthesize(String text) {
                edgeCalls.incrementAndGet();
                return Path.of("edge.mp3");
            }
        };
        TtsService service = new TtsService(
                properties, new ObjectMapper(), List.of(custom, edge));

        assertThatThrownBy(() -> service.synthesizeSegmentToAudio("가현 음성"))
                .isInstanceOf(TtsIdentityMismatchException.class);
        assertThat(edgeCalls).hasValue(0);
    }

    private static TtsProvider provider(String name, boolean ready, Path result, Exception failure) {
        return new TtsProvider() {
            @Override public String name() { return name; }
            @Override public boolean isReady() { return ready; }
            @Override public Path synthesize(String text) throws Exception {
                if (failure != null) throw failure;
                return result;
            }
        };
    }
}
