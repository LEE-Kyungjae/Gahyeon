package com.gahyeonbot.adapters.unreal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.speech.StreamingTranscriptionPort;
import com.gahyeonbot.adapters.headless.GahyeonClientAuthenticationFilter;
import com.gahyeonbot.core.speech.AudioOutput;
import com.gahyeonbot.core.speech.TranscriptionUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = UnrealStreamingSttTransportIntegrationTest.TestApplication.class,
        properties = {
                "gahyeon.headless.enabled=true",
                "gahyeon.unreal.websocket.enabled=true",
                "gahyeon.unreal.streaming-stt.enabled=true",
                "gahyeon.client-auth.token=transport-secret"
        })
class UnrealStreamingSttTransportIntegrationTest {
    @LocalServerPort
    private int port;

    @Autowired
    private RecordingProvider provider;

    @Autowired
    private UnrealAudioCache audioCache;

    @Test
    void bearerTokenProtectsWebSocketUpgradeAndSpeechHttp() throws Exception {
        var client = client();
        assertThatThrownBy(() -> client.execute(
                        new TextWebSocketHandler(), new WebSocketHttpHeaders(), endpoint())
                .get(10, TimeUnit.SECONDS))
                .rootCause()
                .hasMessageContaining("[401]");

        URI status = URI.create("http://127.0.0.1:" + port
                + "/api/gahyeon/unreal/speech/status");
        var denied = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(status).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(denied.headers().firstValue("www-authenticate")).contains("Bearer");

        var allowed = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(status)
                        .header("Authorization", "Bearer transport-secret").GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(allowed.statusCode()).isEqualTo(200);
    }

    @Test
    void cachedTtsAudioIsDownloadableAtThePublishedTransportPath() throws Exception {
        byte[] wav = new byte[]{'R', 'I', 'F', 'F', 1, 2, 3, 4};
        String audioId = audioCache.put(new AudioOutput(wav, "audio/wav", "wav"));
        URI uri = URI.create("http://127.0.0.1:" + port
                + "/api/gahyeon/unreal/speech/audio/" + audioId);

        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri)
                        .header("Authorization", "Bearer transport-secret").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type")).contains("audio/wav");
        assertThat(response.headers().firstValue("cache-control")).contains("no-store");
        assertThat(response.body()).containsExactly(wav);

        audioCache.discard(audioId);
        var missing = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri)
                        .header("Authorization", "Bearer transport-secret").GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(missing.statusCode()).isEqualTo(404);
    }

    @Test
    void embeddedServerAcceptsMaximumProtocolPcmFrame() throws Exception {
        provider.reset();
        var client = client();
        WebSocketSession socket = client.execute(
                        new TextWebSocketHandler(),
                        authorizedHeaders(),
                        endpoint())
                .get(10, TimeUnit.SECONDS);
        try {
            socket.sendMessage(startMessage("maximum-frame"));

            byte[] frame = new byte[UnrealWebSocketConfiguration.MAX_BINARY_MESSAGE_BYTES];
            ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putLong(0L);
            socket.sendMessage(new BinaryMessage(frame));

            assertThat(provider.receivedBytes.poll(10, TimeUnit.SECONDS))
                    .isEqualTo(UnrealStreamingTranscriptionStateMachine.MAX_PCM_BYTES);
        } finally {
            socket.close();
        }
    }

    @Test
    void oversizedFrameClosesOnlyItsConnectionAndFreshConnectionStillWorks() throws Exception {
        provider.reset();
        var client = client();
        var closed = new CountDownLatch(1);
        WebSocketSession oversized = client.execute(
                        new TextWebSocketHandler() {
                            @Override
                            public void afterConnectionClosed(
                                    WebSocketSession session, CloseStatus status) {
                                closed.countDown();
                            }
                        },
                        authorizedHeaders(), endpoint())
                .get(10, TimeUnit.SECONDS);
        oversized.sendMessage(startMessage("oversized-frame"));
        oversized.sendMessage(new BinaryMessage(
                new byte[UnrealWebSocketConfiguration.MAX_BINARY_MESSAGE_BYTES + 4]));

        assertThat(closed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(provider.receivedBytes.poll(200, TimeUnit.MILLISECONDS)).isNull();

        WebSocketSession recovered = client.execute(
                        new TextWebSocketHandler(), authorizedHeaders(), endpoint())
                .get(10, TimeUnit.SECONDS);
        try {
            recovered.sendMessage(startMessage("recovered-frame"));
            byte[] valid = new byte[Long.BYTES + Float.BYTES];
            ByteBuffer.wrap(valid).order(ByteOrder.BIG_ENDIAN).putLong(0L);
            recovered.sendMessage(new BinaryMessage(valid));
            assertThat(provider.receivedBytes.poll(10, TimeUnit.SECONDS))
                    .isEqualTo(Float.BYTES);
        } finally {
            recovered.close();
        }
    }

    @Test
    void completeLifecycleReturnsOrderedPartialAndFinalTranscripts() throws Exception {
        provider.reset();
        var inbound = new LinkedBlockingQueue<String>();
        var client = client();
        WebSocketSession socket = client.execute(
                        new TextWebSocketHandler() {
                            @Override
                            protected void handleTextMessage(
                                    WebSocketSession session, TextMessage message) {
                                inbound.add(message.getPayload());
                            }
                        },
                        authorizedHeaders(), endpoint())
                .get(10, TimeUnit.SECONDS);
        try {
            socket.sendMessage(startMessage("lifecycle"));
            byte[] valid = new byte[Long.BYTES + Float.BYTES];
            ByteBuffer.wrap(valid).order(ByteOrder.BIG_ENDIAN).putLong(0L);
            socket.sendMessage(new BinaryMessage(valid));
            socket.sendMessage(new TextMessage("""
                    {"schemaVersion":1,"type":"stt.stream.end","sessionId":"transport-test",\
                    "streamId":"lifecycle","generation":1,"observedAtMs":2,\
                    "lastAudioSequence":0}
                    """));

            var objectMapper = new ObjectMapper();
            var partial = objectMapper.readTree(inbound.poll(10, TimeUnit.SECONDS));
            var complete = objectMapper.readTree(inbound.poll(10, TimeUnit.SECONDS));
            assertThat(partial.path("type").asText()).isEqualTo("stt.transcript.partial");
            assertThat(partial.path("generation").asLong()).isEqualTo(1);
            assertThat(partial.path("resultSequence").asLong()).isEqualTo(0);
            assertThat(complete.path("type").asText()).isEqualTo("stt.transcript.final");
            assertThat(complete.path("generation").asLong()).isEqualTo(1);
            assertThat(complete.path("resultSequence").asLong()).isEqualTo(1);
            assertThat(complete.path("text").asText()).isEqualTo("통합 전송 완료");
        } finally {
            socket.close();
        }
    }

    @Test
    void newerGenerationRejectsLateTranscriptFromCancelledProviderSession() throws Exception {
        provider.reset();
        var inbound = new LinkedBlockingQueue<String>();
        var client = client();
        WebSocketSession socket = client.execute(
                        new TextWebSocketHandler() {
                            @Override
                            protected void handleTextMessage(
                                    WebSocketSession session, TextMessage message) {
                                inbound.add(message.getPayload());
                            }
                        },
                        authorizedHeaders(), endpoint())
                .get(10, TimeUnit.SECONDS);
        try {
            socket.sendMessage(startMessage("old-generation", 1));
            var oldSession = provider.openedSessions.poll(10, TimeUnit.SECONDS);
            assertThat(oldSession).isNotNull();

            socket.sendMessage(startMessage("current-generation", 2));
            var currentSession = provider.openedSessions.poll(10, TimeUnit.SECONDS);
            assertThat(currentSession).isNotNull();
            assertThat(oldSession.cancelReason)
                    .isEqualTo(StreamingTranscriptionPort.CancelReason.BARGE_IN);

            oldSession.listener.onPartial(0, "폐기되어야 함", 0.9);
            currentSession.listener.onPartial(0, "현재 세대", 0.9);

            var visible = new ObjectMapper().readTree(inbound.poll(10, TimeUnit.SECONDS));
            assertThat(visible.path("generation").asLong()).isEqualTo(2);
            assertThat(visible.path("text").asText()).isEqualTo("현재 세대");
            assertThat(inbound.poll(200, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            socket.close();
        }
    }

    @Test
    void reconnectRejectsLateTranscriptFromClosedSocketProviderSession() throws Exception {
        provider.reset();
        var client = client();
        WebSocketSession first = client.execute(
                        new TextWebSocketHandler(), authorizedHeaders(), endpoint())
                .get(10, TimeUnit.SECONDS);
        first.sendMessage(startMessage("before-reconnect", 7));
        var oldSession = provider.openedSessions.poll(10, TimeUnit.SECONDS);
        assertThat(oldSession).isNotNull();
        first.close();
        assertThat(oldSession.cancelled.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(oldSession.cancelReason)
                .isEqualTo(StreamingTranscriptionPort.CancelReason.CLIENT_RESET);

        var inbound = new LinkedBlockingQueue<String>();
        WebSocketSession reconnected = client.execute(
                        new TextWebSocketHandler() {
                            @Override
                            protected void handleTextMessage(
                                    WebSocketSession session, TextMessage message) {
                                inbound.add(message.getPayload());
                            }
                        },
                        authorizedHeaders(), endpoint())
                .get(10, TimeUnit.SECONDS);
        try {
            reconnected.sendMessage(startMessage("after-reconnect", 7));
            var currentSession = provider.openedSessions.poll(10, TimeUnit.SECONDS);
            assertThat(currentSession).isNotNull();

            oldSession.listener.onPartial(0, "이전 연결", 0.9);
            currentSession.listener.onPartial(0, "새 연결", 0.9);

            var visible = new ObjectMapper().readTree(inbound.poll(10, TimeUnit.SECONDS));
            assertThat(visible.path("generation").asLong()).isEqualTo(7);
            assertThat(visible.path("text").asText()).isEqualTo("새 연결");
            assertThat(inbound.poll(200, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            reconnected.close();
        }
    }

    private URI endpoint() {
        return URI.create("ws://127.0.0.1:" + port + "/api/gahyeon/unreal/stt/v1");
    }

    private static StandardWebSocketClient client() {
        var client = new StandardWebSocketClient();
        client.setUserProperties(java.util.Map.of(
                "org.apache.tomcat.websocket.IO_TIMEOUT_MS", "20000"));
        return client;
    }

    private static WebSocketHttpHeaders authorizedHeaders() {
        var headers = new WebSocketHttpHeaders();
        headers.setBearerAuth("transport-secret");
        return headers;
    }

    private static TextMessage startMessage(String streamId) {
        return startMessage(streamId, 1);
    }

    private static TextMessage startMessage(String streamId, long generation) {
        return new TextMessage("""
                {"schemaVersion":1,"type":"stt.stream.start","sessionId":"transport-test",\
                "streamId":"%s","generation":%d,"observedAtMs":1,\
                "format":{"encoding":"float32le","sampleRate":16000,"channels":1,\
                "framesPerChunk":4096}}
                """.formatted(streamId, generation));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    @Import({
            UnrealWebSocketConfiguration.class,
            RecordingProvider.class,
            UnrealStreamingSttWebSocketConfiguration.class,
            UnrealSpeechController.class,
            GahyeonClientAuthenticationFilter.class
    })
    static class TestApplication {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        UnrealWebSocketHandler unrealWebSocketHandler() {
            return mock(UnrealWebSocketHandler.class);
        }

        @Bean
        UnrealStreamingTranscriptAdmission unrealStreamingTranscriptAdmission() {
            var admission = mock(UnrealStreamingTranscriptAdmission.class);
            when(admission.started(any())).thenReturn(true);
            when(admission.partial(any(), anyString(), anyDouble())).thenReturn(true);
            when(admission.ended(any())).thenReturn(true);
            when(admission.completed(any(), anyString())).thenReturn(true);
            return admission;
        }

        @Bean
        UnrealRuntimeMetrics unrealRuntimeMetrics() {
            return new UnrealRuntimeMetrics(new SimpleMeterRegistry());
        }

        @Bean
        UnrealAudioCache unrealAudioCache() {
            return new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5));
        }

        @Bean
        TranscriptionUseCase transcriptionUseCase() {
            return mock(TranscriptionUseCase.class);
        }
    }

    static final class RecordingProvider implements StreamingTranscriptionPort {
        private final LinkedBlockingQueue<Integer> receivedBytes = new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<ControlledSession> openedSessions =
                new LinkedBlockingQueue<>();

        void reset() {
            receivedBytes.clear();
            openedSessions.clear();
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public Session open(StartRequest request, ResultListener listener) {
            var session = new ControlledSession(listener);
            openedSessions.add(session);
            return session;
        }

        final class ControlledSession implements Session {
            private final ResultListener listener;
            private final CountDownLatch cancelled = new CountDownLatch(1);
            private volatile CancelReason cancelReason;

            private ControlledSession(ResultListener listener) {
                this.listener = listener;
            }

            @Override
            public OfferResult offer(AudioChunk chunk) {
                receivedBytes.add(chunk.pcm().length);
                return OfferResult.ACCEPTED;
            }

            @Override
            public void finish() {
                listener.onPartial(0, "통합", 0.8);
                listener.onFinal(1, "통합 전송 완료", "ko");
            }

            @Override
            public void cancel(CancelReason reason) {
                cancelReason = reason;
                cancelled.countDown();
            }
        }
    }
}
