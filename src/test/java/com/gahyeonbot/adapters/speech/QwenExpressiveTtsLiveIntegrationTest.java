package com.gahyeonbot.adapters.speech;

import com.gahyeonbot.core.speech.ExpressiveSpeechRequest;
import com.gahyeonbot.core.speech.SpeechSegment;
import com.gahyeonbot.core.speech.VoiceExpression;
import com.gahyeonbot.core.speech.VoiceProfileId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QwenExpressiveTtsLiveIntegrationTest {
    @Test
    void callsAttestedLandWorkerWhenExplicitlyEnabled() {
        String endpoint = System.getenv("GAHYEON_QWEN_LIVE_ENDPOINT");
        Assumptions.assumeTrue(endpoint != null && !endpoint.isBlank());

        var properties = new QwenExpressiveTtsProperties();
        properties.setEnabled(true);
        properties.setEndpoint(endpoint);
        properties.setModelId("Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice");
        properties.setQuantization("c-int4-avx2");
        properties.setTimeoutMillis(120_000);
        var adapter = new QwenExpressiveTtsAdapter(properties);

        var natural = adapter.synthesize(new ExpressiveSpeechRequest(
                new SpeechSegment(0, "잠깐만, 지금 확인해 볼게."),
                VoiceProfileId.ASSISTANT,
                VoiceExpression.NATURAL));
        assertThat(natural.mediaType()).isEqualTo("audio/wav");
        assertThat(natural.data()).startsWith('R', 'I', 'F', 'F');
        assertThat(natural.data().length).isGreaterThan(44);

        var bright = adapter.synthesize(new ExpressiveSpeechRequest(
                new SpeechSegment(1, "잠깐만, 지금 확인해 볼게."),
                VoiceProfileId.ASSISTANT,
                new VoiceExpression("bright", 0.75, "reassure")));
        assertThat(bright.mediaType()).isEqualTo("audio/wav");
        assertThat(bright.data()).startsWith('R', 'I', 'F', 'F');
        assertThat(bright.data()).isNotEqualTo(natural.data());

        for (VoiceExpression expression : java.util.List.of(
                new VoiceExpression("surprised", 0.7, "react"),
                new VoiceExpression("annoyed", 0.6, "object"),
                new VoiceExpression("sad", 0.55, "confide"))) {
            var expressive = adapter.synthesize(new ExpressiveSpeechRequest(
                    new SpeechSegment(2, "잠깐만, 지금 확인해 볼게."),
                    VoiceProfileId.ASSISTANT,
                    expression));
            assertThat(expressive.mediaType()).isEqualTo("audio/wav");
            assertThat(expressive.data()).startsWith('R', 'I', 'F', 'F');
            assertThat(expressive.data()).isNotEqualTo(natural.data());
        }
    }
}
