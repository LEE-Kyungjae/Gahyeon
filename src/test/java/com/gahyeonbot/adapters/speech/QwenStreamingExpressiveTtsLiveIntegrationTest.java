package com.gahyeonbot.adapters.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.speech.StreamingExpressiveSpeechSynthesisPort;
import com.gahyeonbot.core.speech.ExpressiveSpeechRequest;
import com.gahyeonbot.core.speech.PcmAudioFormat;
import com.gahyeonbot.core.speech.SpeechSegment;
import com.gahyeonbot.core.speech.VoiceExpression;
import com.gahyeonbot.core.speech.VoiceProfileId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class QwenStreamingExpressiveTtsLiveIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "GAHYEON_QWEN_17B_LIVE_ENDPOINT", matches = ".+")
    void receivesAttestedPcmBeforeTheCompleteUtterance() throws Exception {
        var properties = new QwenExpressiveTtsProperties();
        properties.setEnabled(true);
        properties.setEndpoint(System.getenv("GAHYEON_QWEN_17B_LIVE_ENDPOINT"));
        properties.setApiKey(System.getenv("GAHYEON_QWEN_17B_LIVE_API_KEY"));
        properties.setModelId("Qwen/Qwen3-TTS-12Hz-1.7B-Base");
        properties.setQuantization("c-int4-cuda-sm75-mixed");
        properties.setTimeoutMillis(120_000);
        var adapter = new QwenStreamingExpressiveTtsAdapter(properties, new ObjectMapper());
        var sink = new MeasuringSink();
        long started = System.nanoTime();

        adapter.streamPcm(new ExpressiveSpeechRequest(
                new SpeechSegment(0, "응, 지금 바로 확인하고 있어. 잠깐만 기다려 줘."),
                VoiceProfileId.ASSISTANT,
                new VoiceExpression("fake_cute", 0.72, "playful_reassurance")), () -> true, sink);

        double firstAudioSeconds = (sink.firstChunkAt - started) / 1_000_000_000.0;
        double totalSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
        double audioSeconds = sink.audio.size() / 48_000.0;
        assertThat(sink.format).isEqualTo(PcmAudioFormat.QWEN_MONO_24K_S16LE);
        assertThat(sink.firstChunkAt).isPositive();
        assertThat(sink.completedBytes).isEqualTo(sink.audio.size());
        assertThat(firstAudioSeconds).isLessThan(totalSeconds);
        assertThat(totalSeconds / audioSeconds).isLessThan(1.1);
        String artifact = System.getenv("GAHYEON_QWEN_17B_STREAM_ARTIFACT");
        if (artifact != null && !artifact.isBlank()) {
            Path output = Path.of(artifact).toAbsolutePath().normalize();
            Files.createDirectories(output.getParent());
            Files.write(output, sink.audio.toByteArray());
        }
        System.out.printf(
                "FIRST_AUDIO=%.3f TOTAL=%.3f AUDIO=%.3f RTF=%.3f BYTES=%d SHA256=%s%n",
                firstAudioSeconds, totalSeconds, audioSeconds, totalSeconds / audioSeconds,
                sink.audio.size(), HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(sink.audio.toByteArray())));
    }

    private static final class MeasuringSink implements StreamingExpressiveSpeechSynthesisPort.PcmSink {
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private long firstChunkAt;
        private long completedBytes;
        private PcmAudioFormat format;

        @Override public void started(PcmAudioFormat value) { format = value; }
        @Override public void chunk(byte[] pcm) {
            if (firstChunkAt == 0) firstChunkAt = System.nanoTime();
            audio.writeBytes(pcm);
        }
        @Override public void completed(long pcmBytes) { completedBytes = pcmBytes; }
    }
}
