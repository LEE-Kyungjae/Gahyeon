package com.gahyeonbot.adapters.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.speech.StreamingExpressiveSpeechSynthesisPort;
import com.gahyeonbot.core.speech.ExpressiveSpeechRequest;
import com.gahyeonbot.core.speech.PcmAudioFormat;
import com.gahyeonbot.core.speech.SpeechSegment;
import com.gahyeonbot.core.speech.VoiceExpression;
import com.gahyeonbot.core.speech.VoiceProfileId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QwenStreamingExpressiveTtsAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void streamsBoundedAttestedPcmWithoutWaitingForAWav() throws Exception {
        byte[] pcm = new byte[70_000];
        java.util.Arrays.fill(pcm, (byte) 7);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JsonNode> requests = new ArrayList<>();
        server.createContext("/v1/speech/stream", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, pcm, "gahyeon.assistant");
        });
        server.start();
        var adapter = new QwenStreamingExpressiveTtsAdapter(properties(), mapper);
        var sink = new RecordingSink();

        adapter.streamPcm(request(), () -> true, sink);

        assertThat(sink.format).isEqualTo(PcmAudioFormat.QWEN_MONO_24K_S16LE);
        assertThat(sink.audio.toByteArray()).isEqualTo(pcm);
        assertThat(sink.completedBytes).isEqualTo(pcm.length);
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().path("responseFormat").textValue()).isEqualTo("pcm");
        assertThat(requests.getFirst().path("style").textValue()).isEqualTo("fake_cute");
    }

    @Test
    void rejectsWrongVoiceAttestationBeforePublishingPcm() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/speech/stream", exchange ->
                respond(exchange, new byte[4_800], "someone-else"));
        server.start();
        var sink = new RecordingSink();

        assertThatThrownBy(() -> new QwenStreamingExpressiveTtsAdapter(properties(), mapper)
                .streamPcm(request(), () -> true, sink))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity mismatch");
        assertThat(sink.format).isNull();
        assertThat(sink.audio.size()).isZero();
    }

    private QwenExpressiveTtsProperties properties() {
        var properties = new QwenExpressiveTtsProperties();
        properties.setEnabled(true);
        properties.setEndpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/speech");
        properties.setApiKey("test-secret");
        properties.setModelId("Qwen/Qwen3-TTS-12Hz-1.7B-Base");
        properties.setQuantization("c-int4-cuda-sm75-mixed");
        properties.setTimeoutMillis(5_000);
        return properties;
    }

    private static ExpressiveSpeechRequest request() {
        return new ExpressiveSpeechRequest(new SpeechSegment(0, "싫어어~"), VoiceProfileId.ASSISTANT,
                new VoiceExpression("fake_cute", 0.72, "playful_refusal"));
    }

    private static void respond(HttpExchange exchange, byte[] pcm, String voice) throws java.io.IOException {
        assertThat(exchange.getRequestMethod()).isEqualTo("POST");
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-secret");
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "audio/pcm");
        headers.set("X-Gahyeon-Voice-Profile", voice);
        headers.set("X-Gahyeon-Model-Id", "Qwen/Qwen3-TTS-12Hz-1.7B-Base");
        headers.set("X-Gahyeon-Quantization", "c-int4-cuda-sm75-mixed");
        headers.set("X-Sample-Rate", "24000");
        headers.set("X-Sample-Format", "s16le");
        headers.set("X-Channels", "1");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(pcm);
        exchange.close();
    }

    private static final class RecordingSink implements StreamingExpressiveSpeechSynthesisPort.PcmSink {
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private PcmAudioFormat format;
        private long completedBytes;

        @Override public void started(PcmAudioFormat value) { format = value; }
        @Override public void chunk(byte[] pcm) { audio.writeBytes(pcm); }
        @Override public void completed(long pcmBytes) { completedBytes = pcmBytes; }
    }
}
