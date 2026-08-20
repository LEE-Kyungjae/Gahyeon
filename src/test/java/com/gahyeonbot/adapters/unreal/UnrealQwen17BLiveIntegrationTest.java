package com.gahyeonbot.adapters.unreal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.adapters.speech.QwenExpressiveTtsAdapter;
import com.gahyeonbot.adapters.speech.QwenExpressiveTtsProperties;
import com.gahyeonbot.adapters.unreal.protocol.UnrealEnvelope;
import com.gahyeonbot.core.speech.AudioOutput;
import com.gahyeonbot.core.speech.ExpressiveSpeechRequest;
import com.gahyeonbot.core.speech.ExpressiveSpeechSynthesisUseCase;
import com.gahyeonbot.core.speech.SpeechSegment;
import com.gahyeonbot.core.speech.SpeechSynthesisUseCase;
import com.gahyeonbot.core.speech.VoiceExpression;
import com.gahyeonbot.core.speech.VoiceProfileId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Core adapter -> Qwen 1.7B -> Unreal prepared-event acceptance. */
class UnrealQwen17BLiveIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "GAHYEON_QWEN_17B_LIVE_ENDPOINT", matches = ".+")
    void publishesAttestedExpressiveAudioAndWaveformVisemes() throws Exception {
        String endpoint = System.getenv("GAHYEON_QWEN_17B_LIVE_ENDPOINT");
        String apiKey = System.getenv("GAHYEON_QWEN_17B_LIVE_API_KEY");
        String text = System.getenv().getOrDefault(
                "GAHYEON_QWEN_17B_LIVE_TEXT", "아, 그렇게 나오시겠다? 그럼 나도 조금 귀엽게 말해 줄게.");
        var properties = new QwenExpressiveTtsProperties();
        properties.setEnabled(true);
        properties.setEndpoint(endpoint);
        properties.setApiKey(apiKey);
        properties.setModelId("Qwen/Qwen3-TTS-12Hz-1.7B-Base");
        properties.setQuantization("c-int4-cuda-sm75-mixed");
        properties.setTimeoutMillis(120_000);
        var worker = new QwenExpressiveTtsAdapter(properties);
        var expression = new VoiceExpression("fake_cute", 0.72, "playful_tease");
        var segment = new SpeechSegment(0, text);

        SpeechSynthesisUseCase segmentation = new SpeechSynthesisUseCase() {
            @Override public boolean isReady(VoiceProfileId voiceProfile) { return false; }
            @Override public List<SpeechSegment> prepare(String ignored) { return List.of(segment); }
            @Override public AudioOutput synthesize(SpeechSegment ignored, VoiceProfileId voiceProfile) {
                throw new AssertionError("natural synthesis must not be selected");
            }
        };
        ExpressiveSpeechSynthesisUseCase expressive = new ExpressiveSpeechSynthesisUseCase() {
            @Override public boolean isExpressiveReady(VoiceProfileId voiceProfile) {
                return worker.isReady(voiceProfile);
            }
            @Override public AudioOutput synthesizeExpressive(ExpressiveSpeechRequest request) {
                return worker.synthesize(request);
            }
        };
        var tasks = new ArrayDeque<Runnable>();
        var messages = new ArrayList<UnrealEnvelope>();
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        broker.subscribe("live-1.7b-renderer", "live-1.7b-session", messages::add);
        var cache = new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5));
        var registry = new SimpleMeterRegistry();
        var service = new DefaultUnrealSpeechPreparationService(
                segmentation, expressive, cache, broker, tasks::add,
                new UnrealRuntimeMetrics(registry), new PcmWavKoreanVisemeTimeline());

        long started = System.nanoTime();
        service.prepare(new UnrealSpeechPreparationRequest(
                "live-1.7b-session", "live:1.7b:utterance:0", 1, 0, text,
                VoiceProfileId.ASSISTANT, expression), () -> true);
        tasks.remove().run();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(messages).hasSize(1);
        UnrealEnvelope prepared = messages.getFirst();
        assertThat(prepared.type()).isEqualTo("speech.prepared");
        assertThat(prepared.payload()).containsEntry("voiceProfile", "gahyeon.assistant");
        assertThat(prepared.payload().get("voiceExpression")).isEqualTo(Map.of(
                "style", "fake_cute", "intensity", 0.72,
                "communicativeIntent", "playful_tease"));
        List<?> visemes = (List<?>) prepared.payload().get("visemes");
        assertThat(visemes).isNotEmpty();
        AudioOutput generated = cache.get((String) prepared.payload().get("utteranceId")).orElseThrow();
        assertThat(generated.mediaType()).isEqualTo("audio/wav");
        assertThat(generated.data()).startsWith('R', 'I', 'F', 'F');
        String artifact = System.getenv("GAHYEON_QWEN_17B_LIVE_ARTIFACT");
        if (artifact != null && !artifact.isBlank()) {
            Path output = Path.of(artifact).toAbsolutePath().normalize();
            Files.createDirectories(output.getParent());
            Files.write(output, generated.data());
        }
        System.out.println(new ObjectMapper().writeValueAsString(Map.of(
                "eventType", prepared.type(),
                "voiceProfile", prepared.payload().get("voiceProfile"),
                "expression", prepared.payload().get("voiceExpression"),
                "visemeSource", "waveform-guided",
                "visemeCount", visemes.size(),
                "audioBytes", generated.data().length,
                "elapsedMillis", elapsedMillis,
                "wavSha256", HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(generated.data())))));
    }
}
