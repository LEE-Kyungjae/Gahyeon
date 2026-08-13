package com.gahyeonbot.services.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gahyeonbot.application.speech.StreamingTranscriptionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** OpenAI Realtime transcription adapter; provider wire details do not escape this class. */
@Service
@ConditionalOnProperty(name = "assistant.stt.realtime.enabled", havingValue = "true")
public final class OpenAiRealtimeStreamingTranscriptionProvider
        implements StreamingTranscriptionPort {
    private static final int MAXIMUM_PROVIDER_EVENT_CHARACTERS = 65_536;
    private static final int MAXIMUM_TRANSCRIPT_CHARACTERS = 8_192;
    private final AssistantProperties properties;
    private final ObjectMapper objectMapper;
    private final RealtimeConnector connector;

    public OpenAiRealtimeStreamingTranscriptionProvider(
            AssistantProperties properties,
            ObjectMapper objectMapper) {
        this(properties, objectMapper, defaultConnector());
    }

    OpenAiRealtimeStreamingTranscriptionProvider(
            AssistantProperties properties,
            ObjectMapper objectMapper,
            RealtimeConnector connector) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.connector = Objects.requireNonNull(connector, "connector");
    }

    @Override
    public boolean isReady() {
        var stt = properties.getStt();
        var realtime = stt.getRealtime();
        return properties.isEnabled() && stt.isEnabled() && realtime.isEnabled()
                && hasText(stt.getApiKey()) && hasText(realtime.getUrl())
                && hasText(realtime.getModel()) && validDelay(realtime.getDelay())
                && realtime.getTargetSampleRate() >= 8_000
                && realtime.getTargetSampleRate() <= 192_000
                && realtime.getMaximumPendingSends() >= 4
                && realtime.getMaximumPendingSends() <= 256
                && realtime.getFinalTimeoutSeconds() >= 1
                && realtime.getFinalTimeoutSeconds() <= 60;
    }

    @Override
    public Session open(StartRequest request, ResultListener listener) {
        if (!isReady()) throw new IllegalStateException("Realtime STT is not configured");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(listener, "listener");
        URI uri = URI.create(properties.getStt().getRealtime().getUrl());
        if (!"wss".equalsIgnoreCase(uri.getScheme())
                && !"ws".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Realtime STT URL must use WebSocket");
        }
        return new RealtimeSession(request, listener, uri);
    }

    private final class RealtimeSession implements Session, WebSocket.Listener {
        private final ResultListener listener;
        private final StreamingFloatPcm16Converter converter;
        private final ArrayDeque<String> outbound = new ArrayDeque<>();
        private final int maximumPending;
        private final int finalTimeoutSeconds;
        private final String defaultLanguage;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final StringBuilder inbound = new StringBuilder();
        private final StringBuilder transcript = new StringBuilder();
        private WebSocket socket;
        private CompletableFuture<WebSocket> connectionAttempt;
        private boolean sending;
        private volatile boolean finishing;
        private long resultSequence;

        private RealtimeSession(StartRequest request, ResultListener listener, URI uri) {
            this.listener = listener;
            var stt = properties.getStt();
            var realtime = stt.getRealtime();
            this.maximumPending = realtime.getMaximumPendingSends();
            this.finalTimeoutSeconds = realtime.getFinalTimeoutSeconds();
            this.defaultLanguage = normalizeLanguage(stt.getLanguage());
            this.converter = new StreamingFloatPcm16Converter(
                    request.format(), realtime.getTargetSampleRate());
            outbound.addLast(sessionUpdateJson());
            CompletableFuture<WebSocket> attempt = connector
                    .connect(uri, this, stt.getApiKey().trim()).toCompletableFuture();
            synchronized (this) {
                if (terminal.get()) {
                    attempt.cancel(true);
                } else if (socket == null && !attempt.isDone()) {
                    connectionAttempt = attempt;
                }
            }
            attempt.whenComplete((ignored, error) -> {
                if (error != null && !attempt.isCancelled()) {
                    fail(ErrorCode.PROVIDER_UNAVAILABLE, true);
                }
            });
        }

        @Override
        public OfferResult offer(AudioChunk chunk) {
            if (terminal.get() || finishing) return OfferResult.BACKPRESSURE;
            byte[] pcm16;
            try {
                pcm16 = converter.convert(chunk.pcm());
            } catch (RuntimeException error) {
                fail(ErrorCode.PROVIDER_ERROR, false);
                return OfferResult.BACKPRESSURE;
            }
            if (pcm16.length == 0) return OfferResult.ACCEPTED;
            ObjectNode append = objectMapper.createObjectNode();
            append.put("type", "input_audio_buffer.append");
            append.put("audio", Base64.getEncoder().encodeToString(pcm16));
            return enqueue(append.toString(), false)
                    ? OfferResult.ACCEPTED : OfferResult.BACKPRESSURE;
        }

        @Override
        public void finish() {
            synchronized (this) {
                if (terminal.get() || finishing) {
                    throw new IllegalStateException("Realtime STT session is not active");
                }
                finishing = true;
            }
            ObjectNode commit = objectMapper.createObjectNode();
            commit.put("type", "input_audio_buffer.commit");
            if (!enqueue(commit.toString(), true)) {
                throw new IllegalStateException("Realtime STT commit queue is full");
            }
            CompletableFutureSupport.delayed(finalTimeoutSeconds, () -> {
                if (finishing && !terminal.get()) fail(ErrorCode.PROVIDER_TIMEOUT, true);
            });
        }

        @Override
        public void cancel(CancelReason reason) {
            if (!terminal.compareAndSet(false, true)) return;
            WebSocket current;
            CompletableFuture<WebSocket> opening;
            synchronized (this) {
                outbound.clear();
                current = socket;
                opening = connectionAttempt;
                connectionAttempt = null;
            }
            if (opening != null) opening.cancel(true);
            if (current != null) {
                try {
                    ObjectNode clear = objectMapper.createObjectNode();
                    clear.put("type", "input_audio_buffer.clear");
                    current.sendText(clear.toString(), true)
                            .whenComplete((ignored, error) -> closeQuietly(
                                    current, reason.name().toLowerCase()));
                } catch (RuntimeException sendFailed) {
                    closeQuietly(current, reason.name().toLowerCase());
                }
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            boolean rejected;
            synchronized (this) {
                rejected = terminal.get();
                connectionAttempt = null;
                if (!rejected) socket = webSocket;
            }
            if (rejected) {
                webSocket.abort();
                return;
            }
            try {
                webSocket.request(1);
                drain();
            } catch (RuntimeException openFailed) {
                fail(ErrorCode.PROVIDER_ERROR, true);
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (terminal.get()) return null;
            String complete = null;
            boolean oversized = false;
            synchronized (this) {
                if (data == null || data.length()
                        > MAXIMUM_PROVIDER_EVENT_CHARACTERS - inbound.length()) {
                    inbound.setLength(0);
                    oversized = true;
                } else {
                    inbound.append(data);
                }
                if (!oversized && last) {
                    complete = inbound.toString();
                    inbound.setLength(0);
                }
            }
            if (oversized) {
                fail(ErrorCode.PROVIDER_ERROR, true);
                return null;
            }
            if (complete != null) handleProviderEvent(complete);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!terminal.get()) fail(ErrorCode.PROVIDER_ERROR, true);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(ErrorCode.PROVIDER_ERROR, true);
        }

        private boolean enqueue(String message, boolean terminalControl) {
            synchronized (this) {
                int depth = outbound.size() + (sending ? 1 : 0);
                if (terminal.get() || depth >= maximumPending) {
                    if (terminalControl) fail(ErrorCode.PROVIDER_ERROR, true);
                    return false;
                }
                outbound.addLast(message);
            }
            drain();
            return true;
        }

        private void drain() {
            final WebSocket current;
            final String next;
            synchronized (this) {
                if (terminal.get() || sending || socket == null || outbound.isEmpty()) return;
                sending = true;
                current = socket;
                next = outbound.removeFirst();
            }
            current.sendText(next, true).whenComplete((ignored, error) -> {
                synchronized (RealtimeSession.this) {
                    sending = false;
                }
                if (error != null) fail(ErrorCode.PROVIDER_ERROR, true);
                else drain();
            });
        }

        private void handleProviderEvent(String payload) {
            if (terminal.get()) return;
            try {
                JsonNode event = objectMapper.readTree(payload);
                String type = event.path("type").asText();
                switch (type) {
                    case "conversation.item.input_audio_transcription.delta" -> {
                        String delta = event.path("delta").asText("");
                        if (delta.isEmpty()) return;
                        if (delta.length()
                                > MAXIMUM_TRANSCRIPT_CHARACTERS - transcript.length()) {
                            fail(ErrorCode.PROVIDER_ERROR, true);
                            return;
                        }
                        transcript.append(delta);
                        listener.onPartial(resultSequence++, transcript.toString(), 0);
                    }
                    case "conversation.item.input_audio_transcription.completed" -> {
                        String completed = event.path("transcript").asText("").trim();
                        if (completed.isEmpty()
                                || completed.length() > MAXIMUM_TRANSCRIPT_CHARACTERS) {
                            fail(ErrorCode.PROVIDER_ERROR, true);
                            return;
                        }
                        String language = detectedLanguage(event, defaultLanguage);
                        if (!terminal.compareAndSet(false, true)) return;
                        WebSocket current;
                        CompletableFuture<WebSocket> opening;
                        synchronized (this) {
                            current = socket;
                            outbound.clear();
                            opening = connectionAttempt;
                            connectionAttempt = null;
                        }
                        if (opening != null) opening.cancel(true);
                        try {
                            listener.onFinal(resultSequence++, completed, language);
                        } finally {
                            if (current != null) closeQuietly(current, "complete");
                        }
                    }
                    case "error" -> fail(ErrorCode.PROVIDER_ERROR, true);
                    default -> {
                        // session and rate-limit events do not affect utterance ordering.
                    }
                }
            } catch (RuntimeException | java.io.IOException error) {
                fail(ErrorCode.PROVIDER_ERROR, true);
            }
        }

        private String sessionUpdateJson() {
            return buildSessionUpdate(
                    objectMapper, properties.getStt(), properties.getStt().getRealtime());
        }

        private void fail(ErrorCode code, boolean recoverable) {
            if (!terminal.compareAndSet(false, true)) return;
            WebSocket current;
            CompletableFuture<WebSocket> opening;
            synchronized (this) {
                outbound.clear();
                current = socket;
                opening = connectionAttempt;
                connectionAttempt = null;
            }
            if (opening != null) opening.cancel(true);
            try {
                listener.onError(code, recoverable);
            } finally {
                if (current != null) current.abort();
            }
        }

        private void closeQuietly(WebSocket current, String reason) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, reason)
                        .whenComplete((ignored, error) -> {
                            if (error != null) current.abort();
                        });
            } catch (RuntimeException closeFailed) {
                current.abort();
            }
        }
    }

    static String buildSessionUpdate(
            ObjectMapper objectMapper,
            AssistantProperties.Stt stt,
            AssistantProperties.Realtime realtime) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "session.update");
        ObjectNode session = root.putObject("session");
        session.put("type", "transcription");
        ObjectNode input = session.putObject("audio").putObject("input");
        input.putObject("format")
                .put("type", "audio/pcm")
                .put("rate", realtime.getTargetSampleRate());
        ObjectNode transcription = input.putObject("transcription");
        transcription.put("model", realtime.getModel().trim());
        transcription.put("delay", realtime.getDelay().trim());
        if (hasText(stt.getPrompt())) transcription.put("prompt", stt.getPrompt().trim());
        if (hasText(stt.getLanguage())) {
            transcription.putArray("languages").add(stt.getLanguage().trim());
        }
        input.putNull("turn_detection");
        return root.toString();
    }

    static String detectedLanguage(JsonNode event, String fallback) {
        JsonNode languages = event.path("languages");
        if (languages.isArray() && !languages.isEmpty()) {
            String code = languages.get(0).path("code").asText("");
            if (code.matches("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")) return code;
        }
        return fallback;
    }

    private static String normalizeLanguage(String language) {
        String value = hasText(language) ? language.trim() : "ko";
        return value.matches("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$") ? value : "ko";
    }

    private static boolean validDelay(String delay) {
        return "minimal".equals(delay) || "low".equals(delay) || "medium".equals(delay)
                || "high".equals(delay) || "xhigh".equals(delay);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    interface RealtimeConnector {
        CompletionStage<WebSocket> connect(URI uri, WebSocket.Listener listener, String apiKey);
    }

    private static RealtimeConnector defaultConnector() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
        return (uri, listener, apiKey) -> client.newWebSocketBuilder()
                .header("Authorization", "Bearer " + apiKey)
                .buildAsync(uri, listener);
    }

    private static final class CompletableFutureSupport {
        private static void delayed(int seconds, Runnable action) {
            java.util.concurrent.CompletableFuture.delayedExecutor(seconds, TimeUnit.SECONDS)
                    .execute(action);
        }
    }
}
