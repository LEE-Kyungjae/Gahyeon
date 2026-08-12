package com.gahyeonbot.adapters.unreal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.speech.StreamingTranscriptionPort;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Authenticated WebSocket transport for the provider-neutral Streaming STT v1 protocol. */
public final class UnrealStreamingSttWebSocketHandler extends AbstractWebSocketHandler {
    private static final Set<String> BASE_FIELDS =
            Set.of("schemaVersion", "type", "sessionId", "streamId", "generation");
    private static final int DEFAULT_MAXIMUM_STREAM_SECONDS = 120;
    private static final int DEFAULT_MAXIMUM_CONNECTIONS = 32;
    private static final int DEFAULT_INITIAL_START_SECONDS = 10;
    private static final int DEFAULT_OUTBOUND_QUEUE_CAPACITY = 64;
    private static final CloseStatus CAPACITY_EXCEEDED =
            new CloseStatus(1013, "streaming_stt_capacity");
    private static final ScheduledExecutorService DEFAULT_DEADLINES =
            Executors.newSingleThreadScheduledExecutor(task -> {
                var thread = new Thread(task, "gahyeon-unreal-streaming-stt-default-deadline");
                thread.setDaemon(true);
                return thread;
            });
    private final ObjectMapper objectMapper;
    private final StreamingTranscriptionPort provider;
    private final UnrealStreamingTranscriptAdmission admission;
    private final UnrealRuntimeMetrics metrics;
    private final LongSupplier nanoTime;
    private final ScheduledExecutorService deadlineScheduler;
    private final int maximumStreamSeconds;
    private final int maximumConnections;
    private final int initialStartSeconds;
    private final Executor outboundExecutor;
    private final int outboundQueueCapacity;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();

    public UnrealStreamingSttWebSocketHandler(
            ObjectMapper objectMapper,
            StreamingTranscriptionPort provider) {
        this(objectMapper, provider, null, null, System::nanoTime,
                DEFAULT_DEADLINES, DEFAULT_MAXIMUM_STREAM_SECONDS, DEFAULT_MAXIMUM_CONNECTIONS);
    }

    public UnrealStreamingSttWebSocketHandler(
            ObjectMapper objectMapper,
            StreamingTranscriptionPort provider,
            UnrealStreamingTranscriptAdmission admission) {
        this(objectMapper, provider, admission, null, System::nanoTime,
                DEFAULT_DEADLINES, DEFAULT_MAXIMUM_STREAM_SECONDS, DEFAULT_MAXIMUM_CONNECTIONS);
    }

    public UnrealStreamingSttWebSocketHandler(
            ObjectMapper objectMapper,
            StreamingTranscriptionPort provider,
            UnrealStreamingTranscriptAdmission admission,
            UnrealRuntimeMetrics metrics) {
        this(objectMapper, provider, admission, metrics, System::nanoTime,
                DEFAULT_DEADLINES, DEFAULT_MAXIMUM_STREAM_SECONDS, DEFAULT_MAXIMUM_CONNECTIONS);
    }

    UnrealStreamingSttWebSocketHandler(
            ObjectMapper objectMapper,
            StreamingTranscriptionPort provider,
            UnrealStreamingTranscriptAdmission admission,
            UnrealRuntimeMetrics metrics,
            LongSupplier nanoTime) {
        this(objectMapper, provider, admission, metrics, nanoTime,
                DEFAULT_DEADLINES, DEFAULT_MAXIMUM_STREAM_SECONDS, DEFAULT_MAXIMUM_CONNECTIONS);
    }

    UnrealStreamingSttWebSocketHandler(
            ObjectMapper objectMapper,
            StreamingTranscriptionPort provider,
            UnrealStreamingTranscriptAdmission admission,
            UnrealRuntimeMetrics metrics,
            LongSupplier nanoTime,
            ScheduledExecutorService deadlineScheduler,
            int maximumStreamSeconds) {
        this(objectMapper, provider, admission, metrics, nanoTime, deadlineScheduler,
                maximumStreamSeconds, DEFAULT_MAXIMUM_CONNECTIONS, DEFAULT_INITIAL_START_SECONDS);
    }

    UnrealStreamingSttWebSocketHandler(
            ObjectMapper objectMapper,
            StreamingTranscriptionPort provider,
            UnrealStreamingTranscriptAdmission admission,
            UnrealRuntimeMetrics metrics,
            LongSupplier nanoTime,
            ScheduledExecutorService deadlineScheduler,
            int maximumStreamSeconds,
            int maximumConnections) {
        this(objectMapper, provider, admission, metrics, nanoTime, deadlineScheduler,
                maximumStreamSeconds, maximumConnections, DEFAULT_INITIAL_START_SECONDS);
    }

    UnrealStreamingSttWebSocketHandler(
            ObjectMapper objectMapper,
            StreamingTranscriptionPort provider,
            UnrealStreamingTranscriptAdmission admission,
            UnrealRuntimeMetrics metrics,
            LongSupplier nanoTime,
            ScheduledExecutorService deadlineScheduler,
            int maximumStreamSeconds,
            int maximumConnections,
            int initialStartSeconds) {
        this(objectMapper, provider, admission, metrics, nanoTime, deadlineScheduler,
                maximumStreamSeconds, maximumConnections, initialStartSeconds,
                Runnable::run, DEFAULT_OUTBOUND_QUEUE_CAPACITY);
    }

    UnrealStreamingSttWebSocketHandler(
            ObjectMapper objectMapper,
            StreamingTranscriptionPort provider,
            UnrealStreamingTranscriptAdmission admission,
            UnrealRuntimeMetrics metrics,
            LongSupplier nanoTime,
            ScheduledExecutorService deadlineScheduler,
            int maximumStreamSeconds,
            int maximumConnections,
            int initialStartSeconds,
            Executor outboundExecutor,
            int outboundQueueCapacity) {
        if (maximumStreamSeconds < 1) {
            throw new IllegalArgumentException("maximumStreamSeconds must be positive");
        }
        if (maximumConnections < 1) {
            throw new IllegalArgumentException("maximumConnections must be positive");
        }
        if (initialStartSeconds < 1) {
            throw new IllegalArgumentException("initialStartSeconds must be positive");
        }
        if (outboundExecutor == null || outboundQueueCapacity < 1) {
            throw new IllegalArgumentException("bounded outbound executor is required");
        }
        this.objectMapper = objectMapper;
        this.provider = provider;
        this.admission = admission;
        this.metrics = metrics;
        this.nanoTime = nanoTime;
        this.deadlineScheduler = deadlineScheduler;
        this.maximumStreamSeconds = maximumStreamSeconds;
        this.maximumConnections = maximumConnections;
        this.initialStartSeconds = initialStartSeconds;
        this.outboundExecutor = outboundExecutor;
        this.outboundQueueCapacity = outboundQueueCapacity;
    }

    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (connections.size() >= maximumConnections) {
            if (metrics != null) metrics.streamingSttConnectionRejected("capacity");
            session.close(CAPACITY_EXCEEDED);
            return;
        }
        Connection connection = new Connection(session);
        connections.put(session.getId(), connection);
        if (metrics != null) metrics.streamingSttConnected(session.getId());
        connection.armInitialDeadline();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Connection connection = connection(session);
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            validateBase(root);
            var key = key(root);
            connection.bind(key.sessionId());
            switch (root.path("type").asText()) {
                case "stt.stream.start" -> {
                    if (connection.machine.start(parseStart(root, key))) {
                        connection.armDeadline(key);
                    }
                }
                case "stt.stream.end" -> {
                    requireFields(root, union(BASE_FIELDS, "observedAtMs", "lastAudioSequence"));
                    nonNegativeLong(root, "observedAtMs");
                    connection.machine.end(key, nonNegativeLong(root, "lastAudioSequence"));
                }
                case "stt.stream.cancel" -> {
                    requireFields(root, union(BASE_FIELDS, "reason"));
                    connection.machine.cancel(key, cancelReason(requiredText(root, "reason")));
                }
                default -> throw new ProtocolFailure("invalid_lifecycle");
            }
        } catch (ProtocolFailure | IllegalArgumentException error) {
            connection.failConnection("invalid_lifecycle");
        } catch (IOException error) {
            connection.failConnection("invalid_lifecycle");
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Connection connection = connection(session);
        ByteBuffer payload = message.getPayload().asReadOnlyBuffer();
        byte[] frame = new byte[payload.remaining()];
        payload.get(frame);
        connection.machine.acceptBinary(frame);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        Connection removed = connections.remove(session.getId());
        if (removed != null) removed.close();
        if (metrics != null) metrics.streamingSttDisconnected(session.getId());
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Connection removed = connections.remove(session.getId());
        if (removed != null) removed.close();
        if (metrics != null) metrics.streamingSttDisconnected(session.getId());
    }

    private Connection connection(WebSocketSession session) {
        Connection result = connections.get(session.getId());
        if (result == null) throw new IllegalStateException("Streaming STT connection is not active");
        return result;
    }

    private StreamingTranscriptionPort.StartRequest parseStart(
            JsonNode root,
            StreamingTranscriptionPort.StreamKey key) {
        requireFields(root, union(BASE_FIELDS, "observedAtMs", "format"));
        JsonNode format = root.path("format");
        requireFields(format, Set.of("encoding", "sampleRate", "channels", "framesPerChunk"));
        var audioFormat = new StreamingTranscriptionPort.AudioFormat(
                requiredText(format, "encoding"),
                exactInt(format, "sampleRate"),
                exactInt(format, "channels"),
                exactInt(format, "framesPerChunk"));
        return new StreamingTranscriptionPort.StartRequest(
                key, nonNegativeLong(root, "observedAtMs"), audioFormat);
    }

    private void validateBase(JsonNode root) {
        JsonNode schemaVersion = root == null ? null : root.path("schemaVersion");
        if (root == null || !root.isObject() || schemaVersion == null
                || !schemaVersion.isIntegralNumber() || !schemaVersion.canConvertToInt()
                || schemaVersion.intValue() != 1) {
            throw new ProtocolFailure("invalid_lifecycle");
        }
        requiredText(root, "type");
        requiredText(root, "sessionId");
        requiredText(root, "streamId");
        nonNegativeLong(root, "generation");
    }

    private StreamingTranscriptionPort.StreamKey key(JsonNode root) {
        return new StreamingTranscriptionPort.StreamKey(
                requiredText(root, "sessionId"),
                requiredText(root, "streamId"),
                nonNegativeLong(root, "generation"));
    }

    private static StreamingTranscriptionPort.CancelReason cancelReason(String value) {
        return switch (value) {
            case "barge_in" -> StreamingTranscriptionPort.CancelReason.BARGE_IN;
            case "client_reset" -> StreamingTranscriptionPort.CancelReason.CLIENT_RESET;
            case "backpressure" -> StreamingTranscriptionPort.CancelReason.BACKPRESSURE;
            case "timeout" -> StreamingTranscriptionPort.CancelReason.TIMEOUT;
            case "capture_error" -> StreamingTranscriptionPort.CancelReason.CAPTURE_ERROR;
            default -> throw new ProtocolFailure("invalid_lifecycle");
        };
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new ProtocolFailure("invalid_lifecycle");
        }
        return value.textValue();
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new ProtocolFailure("invalid_lifecycle");
        }
        return value.longValue();
    }

    private static int exactInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new ProtocolFailure("invalid_lifecycle");
        }
        return value.intValue();
    }

    private static void requireFields(JsonNode node, Set<String> expected) {
        if (!node.isObject()) throw new ProtocolFailure("invalid_lifecycle");
        Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new ProtocolFailure("invalid_lifecycle");
    }

    private static Set<String> union(Set<String> base, String... values) {
        Set<String> result = new java.util.HashSet<>(base);
        result.addAll(Set.of(values));
        return Set.copyOf(result);
    }

    private final class Connection implements UnrealStreamingTranscriptionStateMachine.EventSink {
        private final WebSocketSession session;
        private final UnrealStreamingTranscriptionStateMachine machine;
        private final SerialOutbound outbound;
        private String boundSessionId;
        private StreamingTranscriptionPort.StreamKey measuredKey;
        private long startedNanos;
        private boolean firstPartialObserved;
        private ScheduledFuture<?> deadline;
        private StreamingTranscriptionPort.StreamKey deadlineKey;
        private long deadlineVersion;

        private Connection(WebSocketSession session) {
            this.session = session;
            this.machine = new UnrealStreamingTranscriptionStateMachine(provider, this);
            this.outbound = new SerialOutbound();
        }

        private synchronized void bind(String sessionId) {
            if (boundSessionId == null) boundSessionId = sessionId;
            else if (!boundSessionId.equals(sessionId)) throw new ProtocolFailure("invalid_lifecycle");
        }

        @Override
        public boolean started(StreamingTranscriptionPort.StreamKey key) {
            boolean accepted = admission == null || admission.started(key);
            if (accepted) {
                measuredKey = key;
                startedNanos = nanoTime.getAsLong();
                firstPartialObserved = false;
                metricEvent("started");
            }
            return accepted;
        }

        @Override
        public boolean partial(StreamingTranscriptionPort.StreamKey key, long sequence,
                               String text, double stability) {
            if (admission != null && !admission.partial(key, text, stability)) return false;
            metricEvent("partial");
            if (!firstPartialObserved && key.equals(measuredKey)) {
                firstPartialObserved = true;
                if (metrics != null) metrics.streamingSttFirstPartial(elapsed());
            }
            send(Map.of(
                    "schemaVersion", 1, "type", "stt.transcript.partial",
                    "sessionId", key.sessionId(), "streamId", key.streamId(),
                    "generation", key.generation(), "resultSequence", sequence,
                    "text", text, "stability", stability));
            return true;
        }

        @Override
        public boolean ended(StreamingTranscriptionPort.StreamKey key) {
            return admission == null || admission.ended(key);
        }

        @Override
        public boolean completed(StreamingTranscriptionPort.StreamKey key, long sequence,
                                 String text, String language) {
            if (admission != null && !admission.completed(key, text)) return false;
            metricEvent("final");
            completeMetric(key, "final");
            cancelDeadline(key);
            send(Map.of(
                    "schemaVersion", 1, "type", "stt.transcript.final",
                    "sessionId", key.sessionId(), "streamId", key.streamId(),
                    "generation", key.generation(), "resultSequence", sequence,
                    "text", text, "language", language));
            return true;
        }

        @Override
        public void error(StreamingTranscriptionPort.StreamKey key,
                          UnrealStreamingTranscriptionStateMachine.ErrorCode code,
                          boolean recoverable) {
            if (admission != null) admission.failed(key);
            metricEvent("error");
            if (metrics != null) metrics.streamingSttFailure(code.wireValue());
            completeMetric(key, code.wireValue());
            cancelDeadline(key);
            send(Map.of(
                    "schemaVersion", 1, "type", "stt.stream.error",
                    "sessionId", key.sessionId(), "streamId", key.streamId(),
                    "generation", key.generation(), "code", code.wireValue(),
                    "recoverable", recoverable));
        }

        @Override
        public void connectionError(UnrealStreamingTranscriptionStateMachine.ErrorCode code) {
            metricEvent("error");
            if (metrics != null) metrics.streamingSttFailure(code.wireValue());
            failConnection(code.wireValue());
        }

        @Override
        public void cancelled(StreamingTranscriptionPort.StreamKey key,
                              StreamingTranscriptionPort.CancelReason reason) {
            if (admission != null) admission.failed(key);
            metricEvent("cancelled");
            completeMetric(key, "cancelled");
            cancelDeadline(key);
        }

        private void armDeadline(StreamingTranscriptionPort.StreamKey key) {
            cancelDeadline(null);
            boolean unavailable = false;
            try {
                synchronized (this) {
                    deadlineKey = key;
                    deadline = deadlineScheduler.schedule(
                            () -> machine.timeout(key), maximumStreamSeconds, TimeUnit.SECONDS);
                }
            } catch (RuntimeException rejected) {
                synchronized (this) {
                    deadline = null;
                    deadlineKey = null;
                }
                unavailable = true;
            }
            if (unavailable) {
                machine.timeout(key);
                return;
            }
            // Never call the synchronized state machine while holding the
            // Connection monitor: provider callbacks take the inverse path.
            if (!machine.isActive(key)) cancelDeadline(key);
        }

        private void armInitialDeadline() {
            boolean unavailable = false;
            try {
                synchronized (this) {
                    long owner = ++deadlineVersion;
                    deadlineKey = null;
                    deadline = deadlineScheduler.schedule(
                            () -> initialDeadlineExpired(owner),
                            initialStartSeconds, TimeUnit.SECONDS);
                }
            } catch (RuntimeException rejected) {
                unavailable = true;
            }
            if (unavailable) {
                if (metrics != null) metrics.streamingSttFailure("deadline_unavailable");
                failConnection("deadline_unavailable");
            }
        }

        private void initialDeadlineExpired(long owner) {
            synchronized (this) {
                if (owner != deadlineVersion || deadlineKey != null) return;
                deadline = null;
                deadlineVersion++;
            }
            if (metrics != null) metrics.streamingSttFailure("initial_timeout");
            failConnection("initial_timeout");
        }

        private synchronized void cancelDeadline(StreamingTranscriptionPort.StreamKey key) {
            if (key != null && !key.equals(deadlineKey)) return;
            deadlineVersion++;
            if (deadline != null) deadline.cancel(false);
            deadline = null;
            deadlineKey = null;
        }

        private void metricEvent(String type) {
            if (metrics != null) metrics.streamingSttEvent(type);
        }

        private long elapsed() {
            return Math.max(0, nanoTime.getAsLong() - startedNanos);
        }

        private void completeMetric(StreamingTranscriptionPort.StreamKey key, String result) {
            if (metrics != null && key.equals(measuredKey)) {
                metrics.streamingSttCompleted(result, elapsed());
            }
            if (key.equals(measuredKey)) measuredKey = null;
        }

        private void send(Map<String, Object> payload) {
            final String json;
            try {
                json = objectMapper.writeValueAsString(payload);
            } catch (RuntimeException | IOException error) {
                failConnection("provider_error");
                return;
            }
            OutboundAdmission admission = outbound.enqueue(() -> sendSerialized(json));
            if (admission != OutboundAdmission.ACCEPTED) {
                if (admission == OutboundAdmission.FULL && metrics != null) {
                    metrics.streamingSttOutboundDetached("queue_full");
                }
                failConnection("backpressure");
            }
        }

        private void sendSerialized(String json) {
            try {
                if (session.isOpen()) session.sendMessage(new TextMessage(json));
            } catch (IOException error) {
                throw new IllegalStateException("streaming STT delivery failed", error);
            }
        }

        private void close() {
            outbound.detach();
            machine.close();
        }

        private void failConnection(String ignoredCode) {
            outbound.detach();
            cancelDeadline(null);
            machine.close();
            connections.remove(session.getId(), this);
            if (metrics != null) metrics.streamingSttDisconnected(session.getId());
            try {
                if (session.isOpen()) session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException ignored) {
                // Transport is already unusable.
            }
        }

        private final class SerialOutbound {
            private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
            private boolean draining;
            private boolean detached;

            OutboundAdmission enqueue(Runnable message) {
                boolean startDrain = false;
                synchronized (this) {
                    if (detached) return OutboundAdmission.REJECTED;
                    if (queue.size() >= outboundQueueCapacity) return OutboundAdmission.FULL;
                    queue.addLast(message);
                    if (!draining) {
                        draining = true;
                        startDrain = true;
                    }
                }
                if (!startDrain) return OutboundAdmission.ACCEPTED;
                try {
                    outboundExecutor.execute(this::drain);
                    return OutboundAdmission.ACCEPTED;
                } catch (RuntimeException rejected) {
                    detach();
                    if (metrics != null) metrics.streamingSttOutboundDetached("executor_rejected");
                    return OutboundAdmission.REJECTED;
                }
            }

            void drain() {
                while (true) {
                    Runnable next;
                    synchronized (this) {
                        if (detached) return;
                        next = queue.pollFirst();
                        if (next == null) {
                            draining = false;
                            return;
                        }
                    }
                    try {
                        next.run();
                    } catch (RuntimeException failed) {
                        detach();
                        if (metrics != null) metrics.streamingSttOutboundDetached("delivery_failed");
                        failConnection("provider_error");
                        return;
                    }
                }
            }

            synchronized void detach() {
                detached = true;
                draining = false;
                queue.clear();
            }
        }

        private enum OutboundAdmission { ACCEPTED, FULL, REJECTED }
    }

    private static final class ProtocolFailure extends RuntimeException {
        private ProtocolFailure(String message) {
            super(message);
        }
    }
}
