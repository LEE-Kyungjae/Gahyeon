package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.speech.StreamingExpressiveSpeechSynthesisPort;
import com.gahyeonbot.core.speech.ExpressiveSpeechRequest;
import com.gahyeonbot.core.speech.PcmAudioFormat;
import com.gahyeonbot.core.speech.SpeechSegment;
import com.gahyeonbot.core.speech.VoiceExpression;
import com.gahyeonbot.core.speech.VoiceProfileId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnrealPcmStreamCacheTest {
    @Test
    void replaysOneLiveSynthesisToMultipleRenderers() throws Exception {
        var tasks = new ArrayDeque<Runnable>();
        var provider = new ControlledProvider();
        var cache = new UnrealPcmStreamCache(provider, tasks::add, Clock.systemUTC(),
                Duration.ofMinutes(1), 2, 20_000);
        String id = cache.start(request(), () -> true);
        tasks.remove().run();

        var first = new ByteArrayOutputStream();
        var second = new ByteArrayOutputStream();
        cache.writeTo(id, first);
        cache.writeTo(id, second);

        assertThat(first.toByteArray()).isEqualTo(provider.audio);
        assertThat(second.toByteArray()).isEqualTo(provider.audio);
        assertThat(cache.format(id)).contains(PcmAudioFormat.QWEN_MONO_24K_S16LE);
        assertThat(cache.entryCount()).isEqualTo(1);
    }

    @Test
    void generationCancellationRevokesTheStream() {
        var tasks = new ArrayDeque<Runnable>();
        var current = new AtomicBoolean(true);
        var cache = new UnrealPcmStreamCache(new ControlledProvider(), tasks::add,
                Clock.systemUTC(), Duration.ofMinutes(1), 2, 20_000);
        String id = cache.start(request(), current::get);
        current.set(false);
        tasks.remove().run();

        assertThatThrownBy(() -> cache.writeTo(id, new ByteArrayOutputStream()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void expiredAndOverflowEntriesFailClosed() {
        var tasks = new ArrayDeque<Runnable>();
        Clock expiredClock = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
        var cache = new UnrealPcmStreamCache(new ControlledProvider(), tasks::add,
                expiredClock, Duration.ofSeconds(1), 1, 20_000);
        cache.start(request(), () -> true);
        assertThatThrownBy(() -> cache.start(request(), () -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("full");
    }

    private static ExpressiveSpeechRequest request() {
        return new ExpressiveSpeechRequest(new SpeechSegment(0, "안녕"), VoiceProfileId.ASSISTANT,
                new VoiceExpression("bright", 0.5, "greet"));
    }

    private static final class ControlledProvider implements StreamingExpressiveSpeechSynthesisPort {
        private final byte[] audio = new byte[9_600];

        @Override public boolean isStreamingReady(VoiceProfileId voiceProfile) { return true; }
        @Override public void streamPcm(
                ExpressiveSpeechRequest request, java.util.function.BooleanSupplier current, PcmSink sink) {
            if (!current.getAsBoolean()) throw new IllegalStateException("cancelled generation");
            sink.started(PcmAudioFormat.QWEN_MONO_24K_S16LE);
            sink.chunk(audio);
            sink.completed(audio.length);
        }
    }
}
