package com.gahyeonbot.adapters.speech;

import com.gahyeonbot.core.speech.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QwenExpressiveTtsAdapterTest {
    @Test
    void sendsBoundedExpressionAndAcceptsOnlyAttestedVoiceAndModel() {
        RestTemplate client = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo("http://land:18770/v1/speech"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"text":"싫어어~","voiceProfile":"gahyeon.assistant","style":"fake_cute",
                         "intensity":0.75,"communicativeIntent":"playful_refusal",
                         "modelId":"Qwen/Qwen3-TTS-small","quantization":"int4","responseFormat":"wav"}
                        """))
                .andRespond(withSuccess(wav(), MediaType.parseMediaType("audio/wav"))
                        .header(QwenExpressiveTtsAdapter.VOICE_PROFILE_HEADER, "gahyeon.assistant")
                        .header(QwenExpressiveTtsAdapter.MODEL_HEADER, "Qwen/Qwen3-TTS-small")
                        .header(QwenExpressiveTtsAdapter.QUANTIZATION_HEADER, "int4"));
        var adapter = new QwenExpressiveTtsAdapter(properties(), client);

        assertThat(adapter.synthesize(request()).mediaType()).isEqualTo("audio/wav");
        server.verify();
    }

    @Test
    void rejectsAResponseFromTheWrongSpeakerIdentity() {
        RestTemplate client = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
        server.expect(anything()).andRespond(withSuccess(wav(), MediaType.parseMediaType("audio/wav"))
                .header(QwenExpressiveTtsAdapter.VOICE_PROFILE_HEADER, "someone-else")
                .header(QwenExpressiveTtsAdapter.MODEL_HEADER, "Qwen/Qwen3-TTS-small")
                .header(QwenExpressiveTtsAdapter.QUANTIZATION_HEADER, "int4"));

        assertThatThrownBy(() -> new QwenExpressiveTtsAdapter(properties(), client).synthesize(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity mismatch");
    }

    private static QwenExpressiveTtsProperties properties() {
        var properties = new QwenExpressiveTtsProperties();
        properties.setEnabled(true);
        properties.setEndpoint("http://land:18770/v1/speech");
        properties.setModelId("Qwen/Qwen3-TTS-small");
        properties.setQuantization("int4");
        return properties;
    }

    private static ExpressiveSpeechRequest request() {
        return new ExpressiveSpeechRequest(new SpeechSegment(0, "싫어어~"), VoiceProfileId.ASSISTANT,
                new VoiceExpression("fake_cute", 0.75, "playful_refusal"));
    }

    private static byte[] wav() {
        byte[] bytes = new byte[44];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        System.arraycopy("WAVE".getBytes(StandardCharsets.US_ASCII), 0, bytes, 8, 4);
        return bytes;
    }
}
