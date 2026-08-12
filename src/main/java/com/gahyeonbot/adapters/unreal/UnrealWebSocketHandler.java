package com.gahyeonbot.adapters.unreal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.adapters.unreal.protocol.UnrealDelivery;
import com.gahyeonbot.adapters.unreal.protocol.UnrealEnvelope;
import com.gahyeonbot.adapters.unreal.protocol.UnrealCorrelationId;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.session.ConversationModality;
import com.gahyeonbot.core.session.ConversationSessionId;
import com.gahyeonbot.application.behavior.WorldActionCoordinator;
import com.gahyeonbot.core.world.WorldPosition;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class UnrealWebSocketHandler extends TextWebSocketHandler {
    static final int REPLAY_BATCH_SIZE = 32;
    static final long MAX_UNACKED_SEQUENCE_WINDOW = 500;
    private static final int MAXIMUM_MESSAGE_ID_CHARACTERS = 80;
    private static final int MAXIMUM_TYPE_CHARACTERS = 100;
    private static final int MAXIMUM_CORRELATION_ID_CHARACTERS = 120;
    private static final int MAXIMUM_WORLD_ID_CHARACTERS = 180;
    private static final int MAXIMUM_INSTALLATION_ID_CHARACTERS = 200;
    private static final int MAXIMUM_PARTIAL_TRANSCRIPT_CHARACTERS = 8_192;
    private static final int MAXIMUM_ACTION_ID_CHARACTERS = 80;
    private static final int MAXIMUM_ACTION_REASON_CHARACTERS = 512;

    private final ObjectMapper objectMapper;
    private final UnrealEventReplayService replay;
    private final UnrealCommandDispatcher commands;
    private final UnrealPerceptionSink perception;
    private final UnrealRuntimeMetrics metrics;
    private final UnrealEphemeralBroker outbound;
    private final UnrealPerceptionSessionTracker perceptionSessions;
    private final UnrealWorldSnapshotProvider worldSnapshots;
    private final UnrealActionCompletionPort actionCompletions;
    private final UnrealClientSessionRegistry clientSessions;
    private final ConcurrentHashMap<String, ConnectionState> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingHello> pendingHello = new ConcurrentHashMap<>();
    private final long helloTimeoutNanos;
    private final long heartbeatTimeoutNanos;

    public UnrealWebSocketHandler(
            ObjectMapper objectMapper,
            UnrealEventReplayService replay,
            UnrealCommandDispatcher commands,
            UnrealPerceptionSink perception,
            UnrealRuntimeMetrics metrics,
            UnrealEphemeralBroker outbound,
            UnrealPerceptionSessionTracker perceptionSessions,
            UnrealWorldSnapshotProvider worldSnapshots,
            UnrealActionCompletionPort actionCompletions) {
        this(objectMapper, replay, commands, perception, metrics, outbound, perceptionSessions,
                worldSnapshots, actionCompletions, new UnrealClientSessionRegistry());
    }

    public UnrealWebSocketHandler(
            ObjectMapper objectMapper,
            UnrealEventReplayService replay,
            UnrealCommandDispatcher commands,
            UnrealPerceptionSink perception,
            UnrealRuntimeMetrics metrics,
            UnrealEphemeralBroker outbound,
            UnrealPerceptionSessionTracker perceptionSessions,
            UnrealWorldSnapshotProvider worldSnapshots,
            UnrealActionCompletionPort actionCompletions,
            UnrealClientSessionRegistry clientSessions) {
        this(objectMapper, replay, commands, perception, metrics, outbound, perceptionSessions,
                worldSnapshots, actionCompletions, clientSessions,
                Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    public UnrealWebSocketHandler(
            ObjectMapper objectMapper,
            UnrealEventReplayService replay,
            UnrealCommandDispatcher commands,
            UnrealPerceptionSink perception,
            UnrealRuntimeMetrics metrics,
            UnrealEphemeralBroker outbound,
            UnrealPerceptionSessionTracker perceptionSessions,
            UnrealWorldSnapshotProvider worldSnapshots,
            UnrealActionCompletionPort actionCompletions,
            UnrealClientSessionRegistry clientSessions,
            Duration helloTimeout) {
        this(objectMapper, replay, commands, perception, metrics, outbound, perceptionSessions,
                worldSnapshots, actionCompletions, clientSessions,
                helloTimeout, Duration.ofSeconds(30));
    }

    public UnrealWebSocketHandler(
            ObjectMapper objectMapper,
            UnrealEventReplayService replay,
            UnrealCommandDispatcher commands,
            UnrealPerceptionSink perception,
            UnrealRuntimeMetrics metrics,
            UnrealEphemeralBroker outbound,
            UnrealPerceptionSessionTracker perceptionSessions,
            UnrealWorldSnapshotProvider worldSnapshots,
            UnrealActionCompletionPort actionCompletions,
            UnrealClientSessionRegistry clientSessions,
            Duration helloTimeout,
            Duration heartbeatTimeout) {
        if (worldSnapshots == null) throw new IllegalArgumentException("worldSnapshots is required");
        if (actionCompletions == null) throw new IllegalArgumentException("actionCompletions is required");
        if (clientSessions == null) throw new IllegalArgumentException("clientSessions is required");
        if (helloTimeout == null || helloTimeout.isZero() || helloTimeout.isNegative()) {
            throw new IllegalArgumentException("helloTimeout must be positive");
        }
        if (heartbeatTimeout == null || heartbeatTimeout.isZero() || heartbeatTimeout.isNegative()) {
            throw new IllegalArgumentException("heartbeatTimeout must be positive");
        }
        this.objectMapper = objectMapper;
        this.replay = replay;
        this.commands = commands;
        this.perception = perception;
        this.metrics = metrics;
        this.outbound = outbound;
        this.perceptionSessions = perceptionSessions;
        this.worldSnapshots = worldSnapshots;
        this.actionCompletions = actionCompletions;
        this.clientSessions = clientSessions;
        this.helloTimeoutNanos = helloTimeout.toNanos();
        this.heartbeatTimeoutNanos = heartbeatTimeout.toNanos();
    }

    public UnrealWebSocketHandler(
            ObjectMapper objectMapper,
            UnrealEventReplayService replay,
            UnrealCommandDispatcher commands,
            UnrealPerceptionSink perception,
            UnrealRuntimeMetrics metrics,
            UnrealEphemeralBroker outbound,
            UnrealPerceptionSessionTracker perceptionSessions,
            UnrealWorldSnapshotProvider worldSnapshots) {
        this(objectMapper, replay, commands, perception, metrics, outbound,
                perceptionSessions, worldSnapshots, UnrealActionCompletionPort.unavailable());
    }

    public UnrealWebSocketHandler(
            ObjectMapper objectMapper,
            UnrealEventReplayService replay,
            UnrealCommandDispatcher commands,
            UnrealPerceptionSink perception,
            UnrealRuntimeMetrics metrics,
            UnrealEphemeralBroker outbound) {
        this(objectMapper, replay, commands, perception, metrics, outbound,
                new UnrealPerceptionSessionTracker(), UnrealWorldSnapshotProvider.unavailable(),
                UnrealActionCompletionPort.unavailable());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        long startedAt = System.nanoTime();
        String observedType = "other";
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            validateEnvelope(root);
            String type = root.path("type").asText();
            observedType = type;
            metrics.received(type);
            ConnectionState active = connections.get(session.getId());
            if (active != null) {
                synchronized (active) {
                    active.lastClientActivityNanos = System.nanoTime();
                }
            }
            switch (type) {
                case "client.hello" -> handleHello(session, root);
                case "client.ack" -> handleAck(session, root);
                case "client.ping" -> handlePing(session, root);
                case "interaction.text.submitted" -> handleConversation(
                        session, root, ConversationModality.TEXT);
                case "perception.transcript.final" -> handleConversation(
                        session, root, ConversationModality.VOICE);
                case "interaction.generation.advanced" -> handleGenerationAdvanced(session, root);
                case "character.action.completed" -> handleActionCompleted(session, root);
                case "perception.voice.started", "perception.voice.ended",
                     "perception.transcript.partial", "perception.user.pose" ->
                        handlePerception(session, root);
                default -> sendError(session, correlation(root), "unsupported_message_type", type);
            }
        } catch (ProtocolException error) {
            metrics.protocolError(error.code);
            sendError(session, error.correlationId, error.code, error.getMessage());
        } catch (Exception error) {
            metrics.protocolError("invalid_json");
            sendError(session, "protocol", "invalid_json", "message is not valid protocol JSON");
        } finally {
            metrics.processing(observedType, System.nanoTime() - startedAt);
        }
    }

    private void handleHello(WebSocketSession session, JsonNode root) throws IOException {
        if (connections.containsKey(session.getId())) {
            throw new ProtocolException(
                    "hello_already_completed",
                    "client.hello may be accepted only once per connection",
                    correlation(root));
        }
        JsonNode payload = root.path("payload");
        String sessionId = boundedText(payload, "sessionId",
                ConversationSessionId.MAXIMUM_EXTERNAL_ID_CHARACTERS, correlation(root));
        String worldId = boundedText(payload, "worldId", MAXIMUM_WORLD_ID_CHARACTERS, correlation(root));
        String installationId = boundedText(payload, "installationId",
                MAXIMUM_INSTALLATION_ID_CHARACTERS, correlation(root));
        String displayName = optionalBoundedText(payload, "displayName", "Gahyeon user",
                ConversationRequest.MAXIMUM_DISPLAY_NAME_CHARACTERS, correlation(root));
        long lastSequence = nonNegativeLong(payload, "lastSequence", correlation(root));
        var state = new ConnectionState(
                new UnrealEventReplayService.UnrealSubscription(sessionId, worldId),
                installationId,
                displayName,
                lastSequence,
                lastSequence,
                session,
                System.nanoTime());
        var binding = new UnrealClientSessionRegistry.Binding(
                sessionId, worldId, installationId, displayName);
        var bindingAdmission = clientSessions.bind(session.getId(), binding);
        if (bindingAdmission == UnrealClientSessionRegistry.BindingAdmission.CONNECTION_ALREADY_BOUND) {
            throw new ProtocolException(
                    "hello_already_completed",
                    "client.hello may be accepted only once per connection",
                    correlation(root));
        }
        if (bindingAdmission
                == UnrealClientSessionRegistry.BindingAdmission.INCOMPATIBLE_SESSION_IDENTITY) {
            throw new ProtocolException(
                    "incompatible_session_identity",
                    "all renderers for a session must share worldId and installationId",
                    correlation(root));
        }
        if (bindingAdmission == UnrealClientSessionRegistry.BindingAdmission.GLOBAL_CAPACITY
                || bindingAdmission == UnrealClientSessionRegistry.BindingAdmission.SESSION_CAPACITY) {
            String reason = bindingAdmission == UnrealClientSessionRegistry.BindingAdmission.GLOBAL_CAPACITY
                    ? "global_capacity" : "session_capacity";
            metrics.rendererConnectionRejected(reason);
            throw new ProtocolException(
                    "renderer_capacity",
                    "renderer connection capacity is exhausted",
                    correlation(root));
        }
        boolean connectionAdded = false;
        try {
            if (connections.putIfAbsent(session.getId(), state) != null) {
                throw new ProtocolException(
                        "hello_already_completed",
                        "client.hello may be accepted only once per connection",
                        correlation(root));
            }
            connectionAdded = true;
            pendingHello.remove(session.getId());
            perception.activateSession(sessionId);
            outbound.subscribe(
                    session.getId(),
                    sessionId,
                    envelope -> {
                        try {
                            send(session, envelope);
                        } catch (IOException error) {
                            throw new UncheckedIOException(error);
                        }
                    },
                    () -> handleFailedSubscriber(session));
            send(session, ephemeral(
                    "server.welcome",
                    sessionId,
                    correlation(root),
                    Map.of(
                            "protocol", UnrealEnvelope.PROTOCOL,
                            "schemaVersion", UnrealEnvelope.SCHEMA_VERSION,
                            "heartbeatIntervalMs", 10_000,
                            "resumeAfter", lastSequence)));
            Map<String, Object> snapshot = worldSnapshots.snapshot(worldId);
            if (!snapshot.isEmpty()) {
                send(session, ephemeral(
                        "world.snapshot",
                        sessionId,
                        "snapshot:" + worldId,
                        snapshot));
            }
        } catch (IOException | RuntimeException failed) {
            if (connectionAdded && connections.remove(session.getId(), state)) {
                releaseIfLastSubscriber(session.getId(), state);
            } else {
                clientSessions.unbind(session.getId());
            }
            throw failed;
        }
    }

    private void handleAck(WebSocketSession session, JsonNode root) {
        ConnectionState state = connections.get(session.getId());
        if (state == null) throw new ProtocolException(
                "hello_required", "client.hello must be sent first", correlation(root));
        long sequence = nonNegativeLong(root.path("payload"), "sequence", correlation(root));
        synchronized (state) {
            if (sequence < state.lastAcknowledged || sequence > state.lastScanned) {
                throw new ProtocolException(
                        "invalid_ack", "ack sequence is outside the sent range", correlation(root));
            }
            state.lastAcknowledged = sequence;
        }
    }

    private void handlePing(WebSocketSession session, JsonNode root) throws IOException {
        ConnectionState state = connections.get(session.getId());
        if (state == null) throw new ProtocolException(
                "hello_required", "client.hello must be sent first", correlation(root));
        if (!UnrealDelivery.EPHEMERAL.wireValue().equals(root.path("delivery").asText())) {
            throw new ProtocolException(
                    "invalid_delivery", "client.ping must use ephemeral delivery", correlation(root));
        }
        requireOnlyFields(root.path("payload"), Set.of(), correlation(root));
        send(session, ephemeral(
                "server.pong",
                state.subscription.sessionId(),
                correlation(root),
                Map.of("clientSentAt", root.path("sentAt").asText())));
    }

    private void handleConversation(
            WebSocketSession session,
            JsonNode root,
            ConversationModality modality) throws IOException {
        ConnectionState state = connections.get(session.getId());
        if (state == null) throw new ProtocolException(
                "hello_required", "client.hello must be sent first", correlation(root));
        if (!UnrealDelivery.COMMAND.wireValue().equals(root.path("delivery").asText())) {
            throw new ProtocolException(
                    "invalid_delivery", "conversation input must use command delivery", correlation(root));
        }
        JsonNode payload = root.path("payload");
        long generation = nonNegativeLong(payload, "generation", correlation(root));
        String text = boundedText(payload, "text",
                ConversationRequest.MAXIMUM_MESSAGE_CHARACTERS, correlation(root));
        String messageId = boundedText(root, "messageId", MAXIMUM_MESSAGE_ID_CHARACTERS, correlation(root));
        String requestId = UnrealCorrelationId.command(generation, messageId);
        if (modality == ConversationModality.TEXT) {
            perceptionSessions.advanceGeneration(state.subscription.sessionId(), generation);
        }
        if (modality == ConversationModality.VOICE) {
            var admission = perceptionSessions.admit(
                    state.subscription.sessionId(), "perception.transcript.final", generation);
            if (admission != UnrealPerceptionSessionTracker.Admission.ACCEPTED) {
                handlePerceptionAdmission(session, state, root, generation, admission);
                return;
            }
        }
        UnrealCommandDispatcher.DispatchResult result = commands.dispatch(new UnrealConversationCommand(
                requestId,
                state.subscription.sessionId(),
                state.installationId,
                state.displayName,
                modality,
                generation,
                text));
        metrics.command(result);
        if (result == UnrealCommandDispatcher.DispatchResult.BACKPRESSURE) {
            if (modality == ConversationModality.VOICE) {
                perceptionSessions.rollbackFinal(state.subscription.sessionId(), generation);
            }
            throw new ProtocolException(
                    "cognition_queue_full", "cognition command was not accepted", correlation(root));
        }
        if (result == UnrealCommandDispatcher.DispatchResult.STALE) {
            send(session, ephemeral(
                    "command.ignored",
                    state.subscription.sessionId(),
                    correlation(root),
                    Map.of("messageId", messageId, "generation", generation, "reason", "stale_generation")));
            return;
        }
        send(session, ephemeral(
                "command.accepted",
                state.subscription.sessionId(),
                correlation(root),
                Map.of(
                        "messageId", messageId,
                        "generation", generation,
                        "backendCorrelationId", requestId,
                        "duplicate", result == UnrealCommandDispatcher.DispatchResult.DUPLICATE)));
    }

    private void handlePerception(WebSocketSession session, JsonNode root) throws IOException {
        ConnectionState state = connections.get(session.getId());
        if (state == null) throw new ProtocolException(
                "hello_required", "client.hello must be sent first", correlation(root));
        if (!UnrealDelivery.EPHEMERAL.wireValue().equals(root.path("delivery").asText())) {
            throw new ProtocolException(
                    "invalid_delivery", "perception input must use ephemeral delivery", correlation(root));
        }
        String type = root.path("type").asText();
        JsonNode payload = root.path("payload");
        long generation = nonNegativeLong(payload, "generation", correlation(root));
        Map<String, Object> copied = switch (type) {
            case "perception.transcript.partial" -> {
                requireOnlyFields(payload, Set.of("generation", "text", "stability"), correlation(root));
                String text = boundedText(payload, "text",
                        MAXIMUM_PARTIAL_TRANSCRIPT_CHARACTERS, correlation(root));
                if (payload.has("stability")) {
                    yield Map.of(
                            "generation", generation,
                            "text", text,
                            "stability", unitDouble(payload, "stability", correlation(root)));
                }
                yield Map.of("generation", generation, "text", text);
            }
            case "perception.user.pose" -> {
                requireOnlyFields(payload, Set.of("generation", "position", "confidence"), correlation(root));
                JsonNode position = payload.path("position");
                if (!position.isObject()) throw new ProtocolException(
                        "invalid_field", "position must be an object", correlation(root));
                requireOnlyFields(position, Set.of("x", "y", "z"), correlation(root));
                Map<String, Double> normalizedPosition = Map.of(
                        "x", finiteDouble(position, "x", correlation(root)),
                        "y", finiteDouble(position, "y", correlation(root)),
                        "z", finiteDouble(position, "z", correlation(root)));
                if (payload.has("confidence")) {
                    yield Map.of(
                            "generation", generation,
                            "position", normalizedPosition,
                            "confidence", unitDouble(payload, "confidence", correlation(root)));
                }
                yield Map.of("generation", generation, "position", normalizedPosition);
            }
            default -> {
                requireOnlyFields(payload, Set.of("generation"), correlation(root));
                yield Map.of("generation", generation);
            }
        };
        var admission = perceptionSessions.admit(
                state.subscription.sessionId(), type, generation);
        if (admission != UnrealPerceptionSessionTracker.Admission.ACCEPTED) {
            handlePerceptionAdmission(session, state, root, generation, admission);
            return;
        }
        if ("perception.voice.started".equals(type)) {
            commands.advanceGeneration(state.subscription.sessionId(), generation);
            metrics.generationAdvanced("voice_started");
        }
        perception.accept(new UnrealPerceptionEvent(
                type,
                state.subscription.sessionId(),
                state.subscription.worldId(),
                generation,
                Instant.now(),
                copied));
        metrics.perception(type);
    }

    private void handleGenerationAdvanced(WebSocketSession session, JsonNode root) throws IOException {
        ConnectionState state = connections.get(session.getId());
        if (state == null) throw new ProtocolException(
                "hello_required", "client.hello must be sent first", correlation(root));
        if (!UnrealDelivery.EPHEMERAL.wireValue().equals(root.path("delivery").asText())) {
            throw new ProtocolException(
                    "invalid_delivery", "generation advance must use ephemeral delivery", correlation(root));
        }
        JsonNode payload = root.path("payload");
        requireOnlyFields(payload, Set.of("generation", "reason"), correlation(root));
        long generation = nonNegativeLong(payload, "generation", correlation(root));
        String reason = requiredText(payload, "reason", correlation(root));
        if (!Set.of("cognition_timeout", "client_reset", "stt_failed",
                "microphone_capture_aborted").contains(reason)) {
            throw new ProtocolException(
                    "invalid_field", "unsupported generation advance reason", correlation(root));
        }
        perceptionSessions.advanceGeneration(state.subscription.sessionId(), generation);
        commands.advanceGeneration(state.subscription.sessionId(), generation);
        metrics.generationAdvanced(reason);
        send(session, ephemeral(
                "generation.advanced",
                state.subscription.sessionId(),
                correlation(root),
                Map.of("generation", generation, "reason", reason)));
    }

    private void handleActionCompleted(WebSocketSession session, JsonNode root) throws IOException {
        ConnectionState state = connections.get(session.getId());
        if (state == null) throw new ProtocolException(
                "hello_required", "client.hello must be sent first", correlation(root));
        if (!UnrealDelivery.COMMAND.wireValue().equals(root.path("delivery").asText())) {
            throw new ProtocolException(
                    "invalid_delivery", "action completion must use command delivery", correlation(root));
        }
        JsonNode payload = root.path("payload");
        requireOnlyFields(payload, Set.of(
                "actionId", "expectedRevision", "outcome", "reason", "finalPosition"), correlation(root));
        String actionId = boundedText(payload, "actionId", MAXIMUM_ACTION_ID_CHARACTERS, correlation(root));
        long expectedRevision = nonNegativeLong(payload, "expectedRevision", correlation(root));
        String outcome = requiredText(payload, "outcome", correlation(root));
        if (!"completed".equals(outcome) && !"failed".equals(outcome)
                && !"cancelled".equals(outcome)) {
            throw new ProtocolException(
                    "invalid_field", "unsupported action outcome", correlation(root));
        }
        JsonNode position = payload.path("finalPosition");
        if (!position.isObject()) throw new ProtocolException(
                "invalid_field", "finalPosition must be an object", correlation(root));
        requireOnlyFields(position, Set.of("x", "y", "z"), correlation(root));
        String reason = optionalBoundedText(
                payload, "reason", "", MAXIMUM_ACTION_REASON_CHARACTERS, correlation(root));
        var completion = new WorldActionCoordinator.ActionCompletion(
                new com.gahyeonbot.core.world.WorldId(state.subscription.worldId()),
                actionId,
                expectedRevision,
                outcome,
                reason,
                new WorldPosition(
                        finiteDouble(position, "x", correlation(root)),
                        finiteDouble(position, "y", correlation(root)),
                        finiteDouble(position, "z", correlation(root))));
        var result = actionCompletions.complete(completion);
        if (result == WorldActionCoordinator.CompletionResult.INVALID) {
            throw new ProtocolException(
                    "invalid_action_completion", "action completion was rejected", correlation(root));
        }
        send(session, ephemeral(
                "character.action.acknowledged",
                state.subscription.sessionId(),
                correlation(root),
                Map.of(
                        "actionId", actionId,
                        "result", result.name().toLowerCase(),
                        "terminal", true,
                        "accepted", result == WorldActionCoordinator.CompletionResult.COMMITTED
                                || result == WorldActionCoordinator.CompletionResult.RECORDED_FAILURE
                                || result == WorldActionCoordinator.CompletionResult.DUPLICATE,
                        "duplicate", result == WorldActionCoordinator.CompletionResult.DUPLICATE)));
    }

    private void handlePerceptionAdmission(
            WebSocketSession session,
            ConnectionState state,
            JsonNode root,
            long generation,
            UnrealPerceptionSessionTracker.Admission admission) throws IOException {
        String reason = admission.name().toLowerCase();
        metrics.perceptionIgnored(reason);
        if (admission == UnrealPerceptionSessionTracker.Admission.INVALID_LIFECYCLE) {
            throw new ProtocolException(
                    "invalid_perception_lifecycle",
                    "perception event does not match the active voice generation",
                    correlation(root));
        }
        send(session, ephemeral(
                "perception.ignored",
                state.subscription.sessionId(),
                correlation(root),
                Map.of(
                        "messageId", root.path("messageId").asText(),
                        "generation", generation,
                        "reason", reason)));
    }

    @Scheduled(fixedDelayString = "${gahyeon.unreal.websocket.poll-millis:100}")
    void deliverEvents() {
        connections.forEach((id, state) -> deliverEvents(id, state));
    }

    private void deliverEvents(String connectionId, ConnectionState state) {
        WebSocketSession session = state.session;
        if (session == null || !session.isOpen()) return;
        synchronized (state) {
            if (state.lastScanned - state.lastAcknowledged >= MAX_UNACKED_SEQUENCE_WINDOW) return;
            try {
                var batch = replay.replay(state.subscription, state.lastScanned, REPLAY_BATCH_SIZE);
                for (UnrealEnvelope envelope : batch.messages()) {
                    if (!outbound.publishTo(connectionId, envelope)) return;
                }
                metrics.replayed(batch.messages().size());
                if (batch.scannedThrough() > state.lastScanned) {
                    if (!outbound.publishTo(connectionId, ephemeral(
                            "stream.cursor",
                            state.subscription.sessionId(),
                            "stream:" + connectionId,
                            Map.of("scannedThrough", batch.scannedThrough())))) return;
                }
                state.lastScanned = batch.scannedThrough();
            } catch (Exception error) {
                handleFailedSubscriber(session);
            }
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!clientSessions.open(session.getId())) {
            metrics.rendererConnectionRejected("global_capacity");
            session.close(new CloseStatus(1013, "renderer_capacity"));
            return;
        }
        pendingHello.put(session.getId(), new PendingHello(
                session, saturatedAdd(System.nanoTime(), helloTimeoutNanos)));
        metrics.connected(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ConnectionState removed = connections.remove(session.getId());
        pendingHello.remove(session.getId());
        releaseIfLastSubscriber(session.getId(), removed);
        metrics.disconnected(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        ConnectionState removed = connections.remove(session.getId());
        pendingHello.remove(session.getId());
        releaseIfLastSubscriber(session.getId(), removed);
        metrics.disconnected(session.getId());
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    @Scheduled(fixedDelayString = "${gahyeon.unreal.websocket.hello-timeout-scan-millis:1000}")
    void expirePendingHelloConnections() {
        expirePendingHelloConnections(System.nanoTime());
    }

    void expirePendingHelloConnections(long nowNanos) {
        pendingHello.forEach((connectionId, pending) -> {
            if (nowNanos - pending.deadlineNanos < 0
                    || !pendingHello.remove(connectionId, pending)) return;
            clientSessions.unbind(connectionId);
            metrics.disconnected(connectionId);
            try {
                if (pending.session.isOpen()) {
                    pending.session.close(new CloseStatus(1008, "hello_timeout"));
                }
            } catch (IOException ignored) {
                // Capacity is already reclaimed locally.
            }
        });
    }

    @Scheduled(fixedDelayString = "${gahyeon.unreal.websocket.heartbeat-timeout-scan-millis:5000}")
    void expireInactiveConnections() {
        expireInactiveConnections(System.nanoTime());
    }

    void expireInactiveConnections(long nowNanos) {
        connections.forEach((connectionId, state) -> {
            synchronized (state) {
                if (nowNanos - state.lastClientActivityNanos < heartbeatTimeoutNanos
                        || !connections.remove(connectionId, state)) return;
            }
            releaseIfLastSubscriber(connectionId, state);
            metrics.disconnected(connectionId);
            try {
                if (state.session.isOpen()) {
                    state.session.close(new CloseStatus(1008, "heartbeat_timeout"));
                }
            } catch (IOException ignored) {
                // Session ownership and capacity were already reclaimed.
            }
        });
    }

    int pendingHelloCount() {
        return pendingHello.size();
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private void releaseIfLastSubscriber(String connectionId, ConnectionState removed) {
        clientSessions.unbind(connectionId);
        String releasedSession = outbound.unsubscribeLastSession(connectionId);
        if (releasedSession == null && removed != null) {
            String candidate = removed.subscription.sessionId();
            if (!outbound.hasSubscribers(candidate)) releasedSession = candidate;
        }
        if (releasedSession != null) {
            commands.releaseSession(releasedSession);
            perceptionSessions.releaseSession(releasedSession);
            perception.releaseSession(releasedSession);
        }
    }

    private void handleFailedSubscriber(WebSocketSession session) {
        String connectionId = session.getId();
        ConnectionState removed = connections.remove(connectionId);
        releaseIfLastSubscriber(connectionId, removed);
        metrics.disconnected(connectionId);
        if (!session.isOpen()) return;
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
            // Session ownership has already been released locally.
        }
    }

    private void validateEnvelope(JsonNode root) {
        String correlation = correlation(root);
        if (!root.isObject()) throw new ProtocolException("invalid_envelope", "root must be an object", correlation);
        if (!UnrealEnvelope.PROTOCOL.equals(root.path("protocol").asText())) {
            throw new ProtocolException("unsupported_protocol", "unsupported protocol", correlation);
        }
        JsonNode schemaVersion = root.path("schemaVersion");
        if (!schemaVersion.isIntegralNumber() || !schemaVersion.canConvertToInt()
                || schemaVersion.intValue() != UnrealEnvelope.SCHEMA_VERSION) {
            throw new ProtocolException("unsupported_schema", "unsupported schema version", correlation);
        }
        boundedText(root, "messageId", MAXIMUM_MESSAGE_ID_CHARACTERS, correlation);
        boundedText(root, "type", MAXIMUM_TYPE_CHARACTERS, correlation);
        instantText(root, "sentAt", correlation);
        boundedText(root, "correlationId", MAXIMUM_CORRELATION_ID_CHARACTERS, correlation);
        String delivery = requiredText(root, "delivery", correlation);
        if (!UnrealDelivery.EPHEMERAL.wireValue().equals(delivery)
                && !UnrealDelivery.COMMAND.wireValue().equals(delivery)) {
            throw new ProtocolException(
                    "invalid_delivery", "client message delivery must be command or ephemeral", correlation);
        }
        if (root.has("sequence")) {
            throw new ProtocolException(
                    "invalid_sequence", "client messages must not carry a durable sequence", correlation);
        }
        if (!root.path("payload").isObject()) {
            throw new ProtocolException("invalid_payload", "payload must be an object", correlation);
        }
    }

    private String requiredText(JsonNode node, String field, String correlation) {
        JsonNode valueNode = node.path(field);
        if (valueNode.isMissingNode() || valueNode.isNull()) throw new ProtocolException(
                "missing_field", field + " is required", correlation);
        if (!valueNode.isTextual()) throw new ProtocolException(
                "invalid_field", field + " must be a string", correlation);
        String value = valueNode.textValue().trim();
        if (value.isBlank()) throw new ProtocolException(
                "missing_field", field + " is required", correlation);
        return value;
    }

    private String boundedText(
            JsonNode node,
            String field,
            int maximumCharacters,
            String correlation) {
        String value = requiredText(node, field, correlation);
        if (value.length() > maximumCharacters) {
            throw new ProtocolException(
                    "invalid_field",
                    field + " must not exceed " + maximumCharacters + " characters",
                    correlation);
        }
        return value;
    }

    private String instantText(JsonNode node, String field, String correlation) {
        String value = requiredText(node, field, correlation);
        try {
            Instant.parse(value);
        } catch (RuntimeException invalid) {
            throw new ProtocolException(
                    "invalid_field", field + " must be an ISO-8601 instant", correlation);
        }
        return value;
    }

    private String optionalBoundedText(
            JsonNode node,
            String field,
            String fallback,
            int maximumCharacters,
            String correlation) {
        JsonNode valueNode = node.path(field);
        if (valueNode.isMissingNode() || valueNode.isNull()) {
            return fallback;
        }
        if (!valueNode.isTextual()) throw new ProtocolException(
                "invalid_field", field + " must be a string", correlation);
        if (valueNode.textValue().isBlank()) return fallback;
        return boundedText(node, field, maximumCharacters, correlation);
    }

    private void requireOnlyFields(
            JsonNode node,
            Set<String> allowedFields,
            String correlation) {
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowedFields.contains(field)) {
                throw new ProtocolException(
                        "invalid_field", "unsupported payload field: " + field, correlation);
            }
        }
    }

    private long nonNegativeLong(JsonNode node, String field, String correlation) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw new ProtocolException("invalid_field", field + " must be a non-negative integer", correlation);
        }
        return value.longValue();
    }

    private double finiteDouble(JsonNode node, String field, String correlation) {
        JsonNode value = node.path(field);
        double number = value.asDouble(Double.NaN);
        if (!value.isNumber() || !Double.isFinite(number)) {
            throw new ProtocolException("invalid_field", field + " must be a finite number", correlation);
        }
        return number;
    }

    private double unitDouble(JsonNode node, String field, String correlation) {
        double number = finiteDouble(node, field, correlation);
        if (number < 0 || number > 1) {
            throw new ProtocolException("invalid_field", field + " must be between 0 and 1", correlation);
        }
        return number;
    }

    private String correlation(JsonNode root) {
        if (root == null || !root.isObject()) return "protocol";
        JsonNode valueNode = root.path("correlationId");
        if (!valueNode.isTextual()) return "protocol";
        String value = valueNode.textValue().trim();
        return value.isBlank() || value.length() > MAXIMUM_CORRELATION_ID_CHARACTERS
                ? "protocol"
                : value;
    }

    private UnrealEnvelope ephemeral(
            String type,
            String sessionId,
            String correlationId,
            Map<String, Object> payload) {
        return new UnrealEnvelope(
                UnrealEnvelope.PROTOCOL,
                UnrealEnvelope.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                type,
                Instant.now(),
                sessionId,
                correlationId,
                UnrealDelivery.EPHEMERAL.wireValue(),
                null,
                payload);
    }

    private void sendError(WebSocketSession session, String correlationId, String code, String detail) {
        try {
            send(session, ephemeral(
                    "protocol.error",
                    null,
                    correlationId == null || correlationId.isBlank() ? "protocol" : correlationId,
                    Map.of("code", code, "detail", detail)));
        } catch (Exception ignored) {
            // Invalid transports are closed by the container.
        }
    }

    private void send(WebSocketSession session, UnrealEnvelope envelope) throws IOException {
        TextMessage message = new TextMessage(objectMapper.writeValueAsString(envelope));
        ConnectionState state = connections.get(session.getId());
        if (state == null) {
            session.sendMessage(message);
            return;
        }
        synchronized (state) {
            session.sendMessage(message);
        }
    }

    int connectionCount() {
        return connections.size();
    }

    private static final class ConnectionState {
        private final UnrealEventReplayService.UnrealSubscription subscription;
        private final String installationId;
        private final String displayName;
        private long lastScanned;
        private long lastAcknowledged;
        private final WebSocketSession session;
        private volatile long lastClientActivityNanos;

        private ConnectionState(
                UnrealEventReplayService.UnrealSubscription subscription,
                String installationId,
                String displayName,
                long lastScanned,
                long lastAcknowledged,
                WebSocketSession session,
                long lastClientActivityNanos) {
            this.subscription = subscription;
            this.installationId = installationId;
            this.displayName = displayName;
            this.lastScanned = lastScanned;
            this.lastAcknowledged = lastAcknowledged;
            this.session = session;
            this.lastClientActivityNanos = lastClientActivityNanos;
        }
    }

    private record PendingHello(WebSocketSession session, long deadlineNanos) {}

    private static final class ProtocolException extends RuntimeException {
        private final String code;
        private final String correlationId;

        private ProtocolException(String code, String message, String correlationId) {
            super(message);
            this.code = code;
            this.correlationId = correlationId;
        }
    }
}
