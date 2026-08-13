package com.gahyeonbot.services.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.speech.StreamingTranscriptionPort;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiRealtimeStreamingTranscriptionProviderTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void remainsUnavailableUntilEveryExplicitRealtimeGateIsConfigured() {
        AssistantProperties properties = properties();
        var provider = new OpenAiRealtimeStreamingTranscriptionProvider(properties, json);
        assertThat(provider.isReady()).isFalse();

        properties.setEnabled(true);
        properties.getStt().setEnabled(true);
        properties.getStt().setApiKey("secret");
        properties.getStt().getRealtime().setEnabled(true);

        assertThat(provider.isReady()).isTrue();
    }

    @Test
    void emitsOfficialTranscriptionSessionShapeWithManualTurnCommit() throws Exception {
        AssistantProperties properties = properties();
        properties.getStt().setPrompt("Gahyeon, Piper");
        var root = json.readTree(OpenAiRealtimeStreamingTranscriptionProvider.buildSessionUpdate(
                json, properties.getStt(), properties.getStt().getRealtime()));

        assertThat(root.path("type").asText()).isEqualTo("session.update");
        var input = root.path("session").path("audio").path("input");
        assertThat(input.path("format").path("type").asText()).isEqualTo("audio/pcm");
        assertThat(input.path("format").path("rate").asInt()).isEqualTo(24_000);
        assertThat(input.path("transcription").path("model").asText())
                .isEqualTo("gpt-live-transcribe");
        assertThat(input.path("transcription").path("delay").asText()).isEqualTo("low");
        assertThat(input.path("transcription").path("languages").get(0).asText())
                .isEqualTo("ko");
        assertThat(input.has("turn_detection") && input.path("turn_detection").isNull()).isTrue();
    }

    @Test
    void usesDetectedLanguageWhenPresentAndSafeFallbackOtherwise() throws Exception {
        assertThat(OpenAiRealtimeStreamingTranscriptionProvider.detectedLanguage(
                json.readTree("{\"languages\":[{\"code\":\"ko-KR\"}]}"), "ko"))
                .isEqualTo("ko-KR");
        assertThat(OpenAiRealtimeStreamingTranscriptionProvider.detectedLanguage(
                json.readTree("{\"languages\":[]}"), "ko"))
                .isEqualTo("ko");
    }

    @Test
    void streamsSessionAudioCommitPartialAndFinalWithoutBlockingOpen() throws Exception {
        AssistantProperties properties = properties();
        properties.setEnabled(true);
        properties.getStt().setEnabled(true);
        properties.getStt().setApiKey("secret");
        properties.getStt().getRealtime().setEnabled(true);
        FakeSocket socket = new FakeSocket();
        OpenAiRealtimeStreamingTranscriptionProvider.RealtimeConnector connector =
                (uri, listener, apiKey) -> {
                    assertThat(uri).isEqualTo(URI.create(
                            "wss://api.openai.com/v1/realtime?model=gpt-live-transcribe"));
                    assertThat(apiKey).isEqualTo("secret");
                    socket.listener = listener;
                    listener.onOpen(socket);
                    return CompletableFuture.completedFuture(socket);
                };
        var provider = new OpenAiRealtimeStreamingTranscriptionProvider(
                properties, json, connector);
        RecordingResults results = new RecordingResults();
        var format = new StreamingTranscriptionPort.AudioFormat(
                "float32le", 24_000, 1, 240);

        StreamingTranscriptionPort.Session session = provider.open(
                new StreamingTranscriptionPort.StartRequest(
                        new StreamingTranscriptionPort.StreamKey("session-1", "stream-1", 1),
                        10, format),
                results);

        assertThat(json.readTree(socket.sent.getFirst()).path("type").asText())
                .isEqualTo("session.update");
        byte[] pcm = ByteBuffer.allocate(240 * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(0, 0.25f)
                .array();
        assertThat(session.offer(new StreamingTranscriptionPort.AudioChunk(0, pcm)))
                .isEqualTo(StreamingTranscriptionPort.OfferResult.ACCEPTED);
        assertThat(json.readTree(socket.sent.get(1)).path("type").asText())
                .isEqualTo("input_audio_buffer.append");
        assertThat(json.readTree(socket.sent.get(1)).path("audio").asText()).isNotBlank();

        socket.emit("""
                {"type":"conversation.item.input_audio_transcription.delta","delta":"안녕"}
                """);
        socket.emit("""
                {"type":"conversation.item.input_audio_transcription.delta","delta":"하세요"}
                """);
        session.finish();
        assertThat(json.readTree(socket.sent.getLast()).path("type").asText())
                .isEqualTo("input_audio_buffer.commit");
        socket.emit("""
                {"type":"conversation.item.input_audio_transcription.completed",\
                "transcript":"안녕하세요","languages":[{"code":"ko"}]}
                """);

        assertThat(results.partials).containsExactly("0:안녕:0.0", "1:안녕하세요:0.0");
        assertThat(results.finals).containsExactly("2:안녕하세요:ko");
        assertThat(results.errors).isEmpty();
        assertThat(socket.closeReason).isEqualTo("complete");
    }

    @Test
    void reportsBackpressureWhileProviderHandshakeIsStalled() {
        AssistantProperties properties = properties();
        properties.setEnabled(true);
        properties.getStt().setEnabled(true);
        properties.getStt().setApiKey("secret");
        properties.getStt().getRealtime().setEnabled(true);
        properties.getStt().getRealtime().setMaximumPendingSends(4);
        var neverConnected = new CompletableFuture<WebSocket>();
        var provider = new OpenAiRealtimeStreamingTranscriptionProvider(
                properties, json, (uri, listener, key) -> neverConnected);
        var format = new StreamingTranscriptionPort.AudioFormat(
                "float32le", 24_000, 1, 240);
        StreamingTranscriptionPort.Session session = provider.open(
                new StreamingTranscriptionPort.StartRequest(
                        new StreamingTranscriptionPort.StreamKey("session-1", "stream-1", 1),
                        10, format),
                new RecordingResults());
        byte[] pcm = new byte[240 * Float.BYTES];

        assertThat(session.offer(new StreamingTranscriptionPort.AudioChunk(0, pcm)))
                .isEqualTo(StreamingTranscriptionPort.OfferResult.ACCEPTED);
        assertThat(session.offer(new StreamingTranscriptionPort.AudioChunk(1, pcm)))
                .isEqualTo(StreamingTranscriptionPort.OfferResult.ACCEPTED);
        assertThat(session.offer(new StreamingTranscriptionPort.AudioChunk(2, pcm)))
                .isEqualTo(StreamingTranscriptionPort.OfferResult.ACCEPTED);
        assertThat(session.offer(new StreamingTranscriptionPort.AudioChunk(3, pcm)))
                .isEqualTo(StreamingTranscriptionPort.OfferResult.BACKPRESSURE);
    }

    @Test
    void cancellationBeforeLateProviderOpenAbortsTheUnownedSocket() {
        AssistantProperties properties = readyProperties();
        var connection = new CompletableFuture<WebSocket>();
        var captured = new WebSocket.Listener[1];
        var provider = new OpenAiRealtimeStreamingTranscriptionProvider(
                properties, json, (uri, listener, key) -> {
                    captured[0] = listener;
                    return connection;
                });
        var session = provider.open(startRequest(), new RecordingResults());
        session.cancel(StreamingTranscriptionPort.CancelReason.CLIENT_RESET);

        assertThat(connection.isCancelled()).isTrue();
        FakeSocket lateSocket = new FakeSocket();
        captured[0].onOpen(lateSocket);

        assertThat(lateSocket.aborted).isTrue();
        assertThat(lateSocket.sent).isEmpty();
    }

    @Test
    void terminalSocketCleanupSurvivesListenerExceptions() {
        AssistantProperties properties = readyProperties();
        FakeSocket finalSocket = new FakeSocket();
        var finalProvider = providerWithSocket(properties, finalSocket);
        finalProvider.open(startRequest(), new StreamingTranscriptionPort.ResultListener() {
            @Override public void onPartial(long sequence, String text, double stability) {}
            @Override public void onFinal(long sequence, String text, String language) {
                throw new IllegalStateException("consumer failed");
            }
            @Override public void onError(
                    StreamingTranscriptionPort.ErrorCode code, boolean recoverable) {}
        });
        finalSocket.emit("""
                {"type":"conversation.item.input_audio_transcription.completed",\
                "transcript":"완료","languages":[{"code":"ko"}]}
                """);
        assertThat(finalSocket.closeReason).isEqualTo("complete");

        FakeSocket errorSocket = new FakeSocket();
        var errorProvider = providerWithSocket(properties, errorSocket);
        errorProvider.open(startRequest(), new StreamingTranscriptionPort.ResultListener() {
            @Override public void onPartial(long sequence, String text, double stability) {}
            @Override public void onFinal(long sequence, String text, String language) {}
            @Override public void onError(
                    StreamingTranscriptionPort.ErrorCode code, boolean recoverable) {
                throw new IllegalStateException("consumer failed");
            }
        });
        errorSocket.emit("{\"type\":\"error\"}");
        assertThat(errorSocket.aborted).isTrue();
    }

    @Test
    void oversizedFragmentedProviderEventFailsAndReleasesSocket() {
        AssistantProperties properties = readyProperties();
        FakeSocket socket = new FakeSocket();
        RecordingResults results = new RecordingResults();
        providerWithSocket(properties, socket).open(startRequest(), results);

        socket.emitFragment("x".repeat(40_000), false);
        socket.emitFragment("y".repeat(30_000), false);

        assertThat(results.errors).containsExactly("PROVIDER_ERROR:true");
        assertThat(socket.aborted).isTrue();
    }

    @Test
    void cumulativeTranscriptIsBoundedBeforeNotifyingConsumer() {
        AssistantProperties properties = readyProperties();
        FakeSocket socket = new FakeSocket();
        RecordingResults results = new RecordingResults();
        providerWithSocket(properties, socket).open(startRequest(), results);

        socket.emit("{\"type\":\"conversation.item.input_audio_transcription.delta\","
                + "\"delta\":\"" + "가".repeat(8_000) + "\"}");
        socket.emit("{\"type\":\"conversation.item.input_audio_transcription.delta\","
                + "\"delta\":\"" + "나".repeat(300) + "\"}");

        assertThat(results.partials).hasSize(1);
        assertThat(results.errors).containsExactly("PROVIDER_ERROR:true");
        assertThat(socket.aborted).isTrue();
    }

    private OpenAiRealtimeStreamingTranscriptionProvider providerWithSocket(
            AssistantProperties properties, FakeSocket socket) {
        return new OpenAiRealtimeStreamingTranscriptionProvider(
                properties, json, (uri, listener, key) -> {
                    socket.listener = listener;
                    listener.onOpen(socket);
                    return CompletableFuture.completedFuture(socket);
                });
    }

    private static StreamingTranscriptionPort.StartRequest startRequest() {
        return new StreamingTranscriptionPort.StartRequest(
                new StreamingTranscriptionPort.StreamKey("session-1", "stream-1", 1),
                10, new StreamingTranscriptionPort.AudioFormat("float32le", 24_000, 1, 240));
    }

    private static AssistantProperties readyProperties() {
        AssistantProperties properties = properties();
        properties.setEnabled(true);
        properties.getStt().setEnabled(true);
        properties.getStt().setApiKey("secret");
        properties.getStt().getRealtime().setEnabled(true);
        return properties;
    }

    private static AssistantProperties properties() {
        AssistantProperties properties = new AssistantProperties();
        properties.getStt().setLanguage("ko");
        return properties;
    }

    private static final class RecordingResults
            implements StreamingTranscriptionPort.ResultListener {
        private final List<String> partials = new ArrayList<>();
        private final List<String> finals = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        @Override
        public void onPartial(long resultSequence, String text, double stability) {
            partials.add(resultSequence + ":" + text + ":" + stability);
        }

        @Override
        public void onFinal(long resultSequence, String text, String language) {
            finals.add(resultSequence + ":" + text + ":" + language);
        }

        @Override
        public void onError(StreamingTranscriptionPort.ErrorCode code, boolean recoverable) {
            errors.add(code + ":" + recoverable);
        }
    }

    private static final class FakeSocket implements WebSocket {
        private final List<String> sent = new ArrayList<>();
        private Listener listener;
        private String closeReason;
        private boolean aborted;

        private void emit(String json) {
            listener.onText(this, json, true);
        }

        private void emitFragment(String value, boolean last) {
            listener.onText(this, value, last);
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sent.add(data.toString());
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            closeReason = reason;
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return closeReason != null || aborted;
        }

        @Override
        public boolean isInputClosed() {
            return closeReason != null || aborted;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }
}
