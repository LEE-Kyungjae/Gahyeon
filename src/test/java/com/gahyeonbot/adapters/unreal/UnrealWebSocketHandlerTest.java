package com.gahyeonbot.adapters.unreal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gahyeonbot.adapters.unreal.protocol.UnrealEventMapper;
import com.gahyeonbot.core.event.EventScope;
import com.gahyeonbot.core.event.GahyeonEvent;
import com.gahyeonbot.core.session.ConversationSessionId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class UnrealWebSocketHandlerTest {
    private final ObjectMapper json = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();
    private final UnrealPerceptionSink perception = event -> {};
    private final UnrealRuntimeMetrics metrics = new UnrealRuntimeMetrics(new SimpleMeterRegistry());
    private final UnrealEphemeralBroker outbound = new UnrealEphemeralBroker(Clock.systemUTC());

    @Test
    void welcomesThenReplaysVisibleEvents() throws Exception {
        var event = event(4, "session-1", "conversation.completed");
        var replay = new UnrealEventReplayService(
                (sequence, limit) -> sequence < 4 ? List.of(event) : List.of(),
                new UnrealEventMapper());
        var handler = new UnrealWebSocketHandler(
                json, replay, command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                perception, metrics, outbound);
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-1", messages);

        handler.handleTextMessage(session, hello(0));
        handler.deliverEvents();

        assertThat(messages).hasSize(3);
        assertThat(parsed(messages.get(0)).path("type").asText()).isEqualTo("server.welcome");
        assertThat(parsed(messages.get(0)).path("payload").path("heartbeatIntervalMs").asInt())
                .isEqualTo(10_000);
        assertThat(parsed(messages.get(0)).path("payload").path("resumeAfter").asLong())
                .isZero();
        assertThat(parsed(messages.get(1)).path("type").asText()).isEqualTo("cognition.response.completed");
        assertThat(parsed(messages.get(1)).path("sequence").asLong()).isEqualTo(4);
        assertThat(parsed(messages.get(2)).path("type").asText()).isEqualTo("stream.cursor");
    }

    @Test
    void sendsAuthoritativeWorldSnapshotImmediatelyAfterWelcome() throws Exception {
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                perception,
                metrics,
                outbound,
                new UnrealPerceptionSessionTracker(),
                worldId -> Map.of(
                        "worldId", worldId,
                        "revision", 17,
                        "currentRoom", "workspace"));
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-snapshot", messages);

        handler.handleTextMessage(session, hello(12));

        assertThat(messages).hasSize(2);
        assertThat(parsed(messages.get(0)).path("type").asText()).isEqualTo("server.welcome");
        JsonNode snapshot = parsed(messages.get(1));
        assertThat(snapshot.path("type").asText()).isEqualTo("world.snapshot");
        assertThat(snapshot.path("delivery").asText()).isEqualTo("ephemeral");
        assertThat(snapshot.path("payload").path("worldId").asText()).isEqualTo("gahyeon-home");
        assertThat(snapshot.path("payload").path("revision").asLong()).isEqualTo(17);
    }

    @Test
    void routesIdempotentActionCompletionToTheWorldCommitBoundary() throws Exception {
        var captured = new AtomicReference<com.gahyeonbot.application.behavior.WorldActionCoordinator.ActionCompletion>();
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                perception,
                metrics,
                outbound,
                new UnrealPerceptionSessionTracker(),
                UnrealWorldSnapshotProvider.unavailable(),
                completion -> {
                    captured.set(completion);
                    return com.gahyeonbot.application.behavior.WorldActionCoordinator.CompletionResult.COMMITTED;
                });
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-action", messages);
        handler.handleTextMessage(session, hello(0));

        handler.handleTextMessage(session, commandEnvelope(
                "character.action.completed",
                Map.of(
                        "actionId", "action-desk",
                        "expectedRevision", 7,
                        "outcome", "completed",
                        "finalPosition", Map.of("x", 7.0, "y", 0.0, "z", -2.0))));

        assertThat(captured.get().actionId()).isEqualTo("action-desk");
        assertThat(captured.get().worldId().value()).isEqualTo("gahyeon-home");
        assertThat(captured.get().expectedRevision()).isEqualTo(7);
        assertThat(captured.get().finalPosition())
                .isEqualTo(new com.gahyeonbot.core.world.WorldPosition(7.0, 0.0, -2.0));
        assertThat(parsed(messages.getLast()).path("type").asText())
                .isEqualTo("character.action.acknowledged");
        assertThat(parsed(messages.getLast()).path("payload").path("result").asText())
                .isEqualTo("committed");
        assertThat(parsed(messages.getLast()).path("payload").path("terminal").asBoolean())
                .isTrue();
        assertThat(parsed(messages.getLast()).path("payload").path("accepted").asBoolean())
                .isTrue();
    }

    @Test
    void rejectsOversizedActionCompletionMetadataBeforeWorldCommit() throws Exception {
        UnrealActionCompletionPort completions = mock(UnrealActionCompletionPort.class);
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                perception,
                metrics,
                outbound,
                new UnrealPerceptionSessionTracker(),
                UnrealWorldSnapshotProvider.unavailable(),
                completions);
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-action-bounded", messages);
        handler.handleTextMessage(session, hello(0));

        handler.handleTextMessage(session, commandEnvelope(
                "character.action.completed",
                Map.of(
                        "actionId", "action-desk",
                        "expectedRevision", 7,
                        "outcome", "failed",
                        "reason", "r".repeat(513),
                        "finalPosition", Map.of("x", 7.0, "y", 0.0, "z", -2.0))));

        verify(completions, never()).complete(any());
        assertThat(parsed(messages.getLast()).path("payload").path("code").asText())
                .isEqualTo("invalid_field");
    }

    @Test
    void rejectsAckBeforeHelloWithoutClosingTheTransport() throws Exception {
        var handler = handlerWithoutEvents();
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-2", messages);

        handler.handleTextMessage(session, envelope("client.ack", Map.of("sequence", 0)));

        assertThat(parsed(messages.getFirst()).path("type").asText()).isEqualTo("protocol.error");
        assertThat(parsed(messages.getFirst()).path("payload").path("code").asText())
                .isEqualTo("hello_required");
    }

    @Test
    void removesConnectionWhenSocketCloses() throws Exception {
        var handler = handlerWithoutEvents();
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-3", messages);
        handler.handleTextMessage(session, hello(0));
        assertThat(handler.connectionCount()).isEqualTo(1);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(handler.connectionCount()).isZero();
    }

    @Test
    void acceptsAckOnlyThroughTheLastScannedSequence() throws Exception {
        var event = event(3, "session-1", "conversation.started");
        var replay = new UnrealEventReplayService(
                (sequence, limit) -> sequence < 3 ? List.of(event) : List.of(),
                new UnrealEventMapper());
        var handler = new UnrealWebSocketHandler(
                json, replay, command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                perception, metrics, outbound);
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-4", messages);
        handler.handleTextMessage(session, hello(0));
        handler.deliverEvents();

        handler.handleTextMessage(session, envelope("client.ack", Map.of("sequence", 4)));

        assertThat(parsed(messages.getLast()).path("payload").path("code").asText())
                .isEqualTo("invalid_ack");
    }

    @Test
    void answersHeartbeatAfterHello() throws Exception {
        var handler = handlerWithoutEvents();
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-5", messages);
        handler.handleTextMessage(session, hello(0));

        handler.handleTextMessage(session, envelope("client.ping", Map.of()));

        assertThat(parsed(messages.getLast()).path("type").asText()).isEqualTo("server.pong");
        assertThat(parsed(messages.getLast()).path("correlationId").asText())
                .isEqualTo("conversation-1");
        assertThat(parsed(messages.getLast()).path("delivery").asText()).isEqualTo("ephemeral");
        assertThat(parsed(messages.getLast()).path("payload").path("clientSentAt").asText())
                .isEqualTo("2026-08-11T03:00:00Z");
    }

    @Test
    void rejectsHeartbeatWithCommandDeliveryOrUnexpectedPayload() throws Exception {
        var handler = handlerWithoutEvents();
        var commandMessages = new ArrayList<TextMessage>();
        WebSocketSession commandSession = session("socket-ping-command", commandMessages);
        handler.handleTextMessage(commandSession, hello(0));
        handler.handleTextMessage(commandSession, commandEnvelope("client.ping", Map.of()));
        assertThat(parsed(commandMessages.getLast()).path("payload").path("code").asText())
                .isEqualTo("invalid_delivery");

        var payloadMessages = new ArrayList<TextMessage>();
        WebSocketSession payloadSession = session("socket-ping-payload", payloadMessages);
        handler.handleTextMessage(payloadSession, hello(0));
        handler.handleTextMessage(payloadSession, envelope("client.ping", Map.of("junk", true)));
        assertThat(parsed(payloadMessages.getLast()).path("payload").path("code").asText())
                .isEqualTo("invalid_field");
    }

    @Test
    void dispatchesConversationWithoutWaitingForCognition() throws Exception {
        var captured = new AtomicReference<UnrealConversationCommand>();
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> {
                    captured.set(command);
                    return UnrealCommandDispatcher.DispatchResult.ACCEPTED;
                },
                perception,
                metrics,
                outbound);
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-6", messages);
        handler.handleTextMessage(session, hello(0));

        handler.handleTextMessage(session, commandEnvelope(
                "interaction.text.submitted",
                Map.of("generation", 2, "text", "안녕")));

        assertThat(captured.get().text()).isEqualTo("안녕");
        assertThat(captured.get().generation()).isEqualTo(2);
        assertThat(captured.get().requestId()).startsWith("unreal:g2:");
        assertThat(parsed(messages.getLast()).path("type").asText()).isEqualTo("command.accepted");
    }

    @Test
    void cognitionBackpressureIsARequestErrorWithoutClosingTransport() throws Exception {
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.BACKPRESSURE,
                perception,
                metrics,
                outbound);
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-backpressure", messages);
        handler.handleTextMessage(session, hello(0));

        handler.handleTextMessage(session, commandEnvelope(
                "interaction.text.submitted",
                Map.of("generation", 2, "text", "포화 테스트")));

        JsonNode error = parsed(messages.getLast());
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        assertThat(error.path("payload").path("code").asText())
                .isEqualTo("cognition_queue_full");
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void rejectsFractionalGenerationInsteadOfTruncatingIt() throws Exception {
        UnrealCommandDispatcher commands = mock(UnrealCommandDispatcher.class);
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                commands,
                perception,
                metrics,
                outbound);
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-fractional-generation", messages);
        handler.handleTextMessage(session, hello(0));

        handler.handleTextMessage(session, commandEnvelope(
                "interaction.text.submitted",
                Map.of("generation", 2.5, "text", "잘리면 안 됩니다")));

        JsonNode error = parsed(messages.getLast());
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        assertThat(error.path("payload").path("code").asText()).isEqualTo("invalid_field");
        verify(commands, never()).dispatch(any());
    }

    @Test
    void rejectsFractionalSchemaVersion() throws Exception {
        var handler = handlerWithoutEvents();
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-fractional-schema", messages);
        TextMessage valid = hello(0);

        handler.handleTextMessage(session, new TextMessage(
                valid.getPayload().replace("\"schemaVersion\":1", "\"schemaVersion\":1.5")));

        assertThat(parsed(messages.getLast()).path("payload").path("code").asText())
                .isEqualTo("unsupported_schema");
        assertThat(handler.connectionCount()).isZero();
    }

    @Test
    void exposesCursorAcrossInvisibleEventsSoTheClientCanAck() throws Exception {
        var invisible = event(500, "other-session", "conversation.started");
        var replay = new UnrealEventReplayService(
                (sequence, limit) -> sequence < 500 ? List.of(invisible) : List.of(),
                new UnrealEventMapper());
        var handler = new UnrealWebSocketHandler(
                json, replay, command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                perception, metrics, outbound);
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-7", messages);
        handler.handleTextMessage(session, hello(0));

        handler.deliverEvents();

        assertThat(messages).hasSize(2);
        JsonNode cursor = parsed(messages.getLast());
        assertThat(cursor.path("type").asText()).isEqualTo("stream.cursor");
        assertThat(cursor.path("payload").path("scannedThrough").asLong()).isEqualTo(500);
        handler.handleTextMessage(session, envelope("client.ack", Map.of("sequence", 500)));
        assertThat(parsed(messages.getLast()).path("type").asText()).isEqualTo("stream.cursor");
    }

    @Test
    void routesPartialTranscriptToVolatilePerceptionWithoutAReply() throws Exception {
        var captured = new AtomicReference<UnrealPerceptionEvent>();
        var registry = new SimpleMeterRegistry();
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                captured::set,
                new UnrealRuntimeMetrics(registry),
                outbound);
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-8", messages);
        handler.handleTextMessage(session, hello(0));
        int before = messages.size();

        handler.handleTextMessage(session, envelope(
                "perception.voice.started",
                Map.of("generation", 4)));

        handler.handleTextMessage(session, envelope(
                "perception.transcript.partial",
                Map.of("generation", 4, "text", "가현아 잠", "stability", 0.75)));

        assertThat(messages).hasSize(before);
        assertThat(captured.get().generation()).isEqualTo(4);
        assertThat(captured.get().payload()).containsEntry("text", "가현아 잠");
        assertThat(registry.get("gahyeon.unreal.perception.events")
                .tag("type", "perception.transcript.partial").counter().count()).isEqualTo(1);
        assertThat(registry.get("gahyeon.unreal.websocket.message.processing")
                .tag("type", "perception.transcript.partial").timer().count()).isEqualTo(1);
    }

    @Test
    void rejectsUnboundedOrUnknownPartialFieldsWithoutAdvancingPerceptionAdmission() throws Exception {
        var captured = new ArrayList<UnrealPerceptionEvent>();
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                captured::add,
                metrics,
                outbound);
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-bounded-partial", messages);
        handler.handleTextMessage(session, hello(0));
        handler.handleTextMessage(session, envelope(
                "perception.voice.started", Map.of("generation", 12)));

        handler.handleTextMessage(session, envelope(
                "perception.transcript.partial",
                Map.of("generation", 12, "text", "가".repeat(8_193))));
        handler.handleTextMessage(session, envelope(
                "perception.transcript.partial",
                Map.of("generation", 12, "text", "정상", "debug", "not-v1")));
        handler.handleTextMessage(session, envelope(
                "perception.transcript.partial",
                Map.of("generation", 12, "text", "정상", "stability", 0.8)));

        assertThat(captured).extracting(UnrealPerceptionEvent::type)
                .containsExactly("perception.voice.started", "perception.transcript.partial");
        assertThat(captured.getLast().payload()).containsOnlyKeys("generation", "text", "stability");
        long invalidFieldErrors = messages.stream()
                .map(message -> {
                    try {
                        return parsed(message);
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                })
                .filter(message -> "protocol.error".equals(message.path("type").asText()))
                .filter(message -> "invalid_field".equals(
                        message.path("payload").path("code").asText()))
                .count();
        assertThat(invalidFieldErrors).isEqualTo(2);
    }

    @Test
    void dispatchesOnlyOneFinalTranscriptForAVoiceGeneration() throws Exception {
        var commands = new ArrayList<UnrealConversationCommand>();
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> {
                    commands.add(command);
                    return UnrealCommandDispatcher.DispatchResult.ACCEPTED;
                },
                perception, metrics, outbound);
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-final", messages);
        handler.handleTextMessage(session, hello(0));
        handler.handleTextMessage(session, envelope(
                "perception.voice.started", Map.of("generation", 5)));
        handler.handleTextMessage(session, envelope(
                "perception.voice.ended", Map.of("generation", 5)));

        handler.handleTextMessage(session, commandEnvelope(
                "perception.transcript.final", Map.of("generation", 5, "text", "최종 문장")));
        handler.handleTextMessage(session, commandEnvelope(
                "perception.transcript.final", Map.of("generation", 5, "text", "중복 문장")));

        assertThat(commands).singleElement().satisfies(command -> {
            assertThat(command.modality()).isEqualTo(com.gahyeonbot.core.session.ConversationModality.VOICE);
            assertThat(command.text()).isEqualTo("최종 문장");
        });
        assertThat(parsed(messages.getLast()).path("type").asText()).isEqualTo("perception.ignored");
        assertThat(parsed(messages.getLast()).path("payload").path("reason").asText())
                .isEqualTo("duplicate");
    }

    @Test
    void ignoresLatePartialFromAPreviousGeneration() throws Exception {
        var captured = new ArrayList<UnrealPerceptionEvent>();
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                captured::add, metrics, outbound);
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-stale-partial", messages);
        handler.handleTextMessage(session, hello(0));
        handler.handleTextMessage(session, envelope(
                "perception.voice.started", Map.of("generation", 6)));
        handler.handleTextMessage(session, envelope(
                "perception.voice.started", Map.of("generation", 7)));

        handler.handleTextMessage(session, envelope(
                "perception.transcript.partial", Map.of("generation", 6, "text", "늦은 조각")));

        assertThat(captured).extracting(UnrealPerceptionEvent::type)
                .containsExactly("perception.voice.started", "perception.voice.started");
        assertThat(parsed(messages.getLast()).path("type").asText()).isEqualTo("perception.ignored");
        assertThat(parsed(messages.getLast()).path("payload").path("reason").asText())
                .isEqualTo("stale");
    }

    @Test
    void vadStartAndAllLocalTerminalPathsAdvanceBackendGenerationImmediately() throws Exception {
        var advanced = new ArrayList<Long>();
        UnrealCommandDispatcher dispatcher = new UnrealCommandDispatcher() {
            @Override
            public DispatchResult dispatch(UnrealConversationCommand command) {
                return DispatchResult.ACCEPTED;
            }

            @Override
            public void advanceGeneration(String sessionId, long generation) {
                advanced.add(generation);
            }
        };
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                dispatcher, perception, metrics, outbound);
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-generation", messages);
        handler.handleTextMessage(session, hello(0));

        handler.handleTextMessage(session, envelope(
                "perception.voice.started", Map.of("generation", 3)));
        handler.handleTextMessage(session, envelope(
                "interaction.generation.advanced",
                Map.of("generation", 4, "reason", "cognition_timeout")));
        handler.handleTextMessage(session, envelope(
                "interaction.generation.advanced",
                Map.of("generation", 5, "reason", "stt_failed")));
        handler.handleTextMessage(session, envelope(
                "interaction.generation.advanced",
                Map.of("generation", 6, "reason", "microphone_capture_aborted")));

        assertThat(advanced).containsExactly(3L, 4L, 5L, 6L);
        assertThat(parsed(messages.getLast()).path("type").asText())
                .isEqualTo("generation.advanced");
        assertThat(parsed(messages.getLast()).path("payload").path("reason").asText())
                .isEqualTo("microphone_capture_aborted");
    }

    @Test
    void connectionGaugeIsIdempotentAcrossErrorAndCloseCallbacks() throws Exception {
        var registry = new SimpleMeterRegistry();
        var localMetrics = new UnrealRuntimeMetrics(registry);
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                perception,
                localMetrics,
                outbound);
        WebSocketSession session = session("socket-9", new ArrayList<>());

        handler.afterConnectionEstablished(session);
        handler.handleTransportError(session, new IOException("closed"));
        handler.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);

        assertThat(registry.get("gahyeon.unreal.websocket.connections").gauge().value()).isZero();
    }

    @Test
    void boundsPhysicalSocketsBeforeHelloAndReusesReleasedCapacity() throws Exception {
        var meterRegistry = new SimpleMeterRegistry();
        var runtimeMetrics = new UnrealRuntimeMetrics(meterRegistry);
        var clientSessions = new UnrealClientSessionRegistry(1, 1);
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                perception,
                runtimeMetrics,
                outbound,
                new UnrealPerceptionSessionTracker(),
                UnrealWorldSnapshotProvider.unavailable(),
                UnrealActionCompletionPort.unavailable(),
                clientSessions);
        WebSocketSession pending = session("pending-without-hello", new ArrayList<>());
        WebSocketSession rejected = session("rejected-before-hello", new ArrayList<>());
        WebSocketSession replacement = session("replacement", new ArrayList<>());

        handler.afterConnectionEstablished(pending);
        handler.afterConnectionEstablished(rejected);

        assertThat(clientSessions.openConnectionCount()).isEqualTo(1);
        verify(rejected).close(new CloseStatus(1013, "renderer_capacity"));
        assertThat(meterRegistry.get("gahyeon.unreal.websocket.connection.rejected")
                .tag("reason", "global_capacity").counter().count()).isEqualTo(1);

        handler.afterConnectionClosed(pending, CloseStatus.NORMAL);
        handler.afterConnectionEstablished(replacement);

        assertThat(clientSessions.openConnectionCount()).isEqualTo(1);
        assertThat(meterRegistry.get("gahyeon.unreal.websocket.connections").gauge().value())
                .isEqualTo(1);
    }

    @Test
    void expiresAConnectionThatNeverSendsHelloAndReclaimsItsSlot() throws Exception {
        var meterRegistry = new SimpleMeterRegistry();
        var runtimeMetrics = new UnrealRuntimeMetrics(meterRegistry);
        var clientSessions = new UnrealClientSessionRegistry(1, 1);
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                perception,
                runtimeMetrics,
                outbound,
                new UnrealPerceptionSessionTracker(),
                UnrealWorldSnapshotProvider.unavailable(),
                UnrealActionCompletionPort.unavailable(),
                clientSessions,
                Duration.ofSeconds(10));
        WebSocketSession idle = session("idle-before-hello", new ArrayList<>());

        handler.afterConnectionEstablished(idle);
        assertThat(handler.pendingHelloCount()).isEqualTo(1);

        handler.expirePendingHelloConnections(Long.MAX_VALUE);

        assertThat(handler.pendingHelloCount()).isZero();
        assertThat(clientSessions.openConnectionCount()).isZero();
        verify(idle).close(new CloseStatus(1008, "hello_timeout"));
        assertThat(meterRegistry.get("gahyeon.unreal.websocket.connections").gauge().value())
                .isZero();
    }

    @Test
    void successfulHelloCancelsThePendingHandshakeDeadline() throws Exception {
        var handler = handlerWithoutEvents();
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("hello-before-deadline", messages);

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, hello(0));
        handler.expirePendingHelloConnections(Long.MAX_VALUE);

        assertThat(handler.pendingHelloCount()).isZero();
        assertThat(handler.connectionCount()).isEqualTo(1);
        verify(session, never()).close(new CloseStatus(1008, "hello_timeout"));
    }

    @Test
    void heartbeatTimeoutRemovesOnlyInactiveRendererAndKeepsSharedSessionAlive() throws Exception {
        UnrealCommandDispatcher commands = mock(UnrealCommandDispatcher.class);
        var localBroker = new UnrealEphemeralBroker(Clock.systemUTC());
        var clientSessions = new UnrealClientSessionRegistry();
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                commands,
                perception,
                metrics,
                localBroker,
                new UnrealPerceptionSessionTracker(),
                UnrealWorldSnapshotProvider.unavailable(),
                UnrealActionCompletionPort.unavailable(),
                clientSessions,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30));
        WebSocketSession inactive = session("inactive-looking-glass", new ArrayList<>());
        var healthyMessages = new ArrayList<TextMessage>();
        WebSocketSession healthy = session("healthy-desktop-heartbeat", healthyMessages);
        handler.handleTextMessage(inactive, hello(0));
        long healthyBoundary = System.nanoTime();
        handler.handleTextMessage(healthy, hello(0));
        handler.handleTextMessage(healthy, envelope("client.ping", Map.of()));

        handler.expireInactiveConnections(healthyBoundary + Duration.ofSeconds(30).toNanos());

        verify(inactive).close(new CloseStatus(1008, "heartbeat_timeout"));
        verify(healthy, never()).close(new CloseStatus(1008, "heartbeat_timeout"));
        assertThat(handler.connectionCount()).isEqualTo(1);
        assertThat(localBroker.subscriberCount()).isEqualTo(1);
        assertThat(clientSessions.connectionCount()).isEqualTo(1);
        verify(commands, never()).releaseSession("session-1");
        assertThat(parsed(healthyMessages.getLast()).path("type").asText()).isEqualTo("server.pong");

        handler.expireInactiveConnections(Long.MAX_VALUE);
        verify(commands).releaseSession("session-1");
    }

    @Test
    void forwardsSessionScopedEphemeralSpeechAndUnsubscribesOnClose() throws Exception {
        var localBroker = new UnrealEphemeralBroker(Clock.systemUTC());
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                perception,
                metrics,
                localBroker);
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-10", messages);
        handler.handleTextMessage(session, hello(0));

        assertThat(localBroker.publish(
                "session-1", "speech.prepared", "unreal:g1:message", Map.of("generation", 1)))
                .isEqualTo(1);
        assertThat(parsed(messages.getLast()).path("type").asText()).isEqualTo("speech.prepared");

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(localBroker.subscriberCount()).isZero();
    }

    @Test
    void releasesSessionWorkOnlyAfterItsLastSocketCloses() throws Exception {
        UnrealCommandDispatcher commands = mock(UnrealCommandDispatcher.class);
        when(commands.dispatch(any())).thenReturn(UnrealCommandDispatcher.DispatchResult.ACCEPTED);
        var localBroker = new UnrealEphemeralBroker(Clock.systemUTC());
        var tracker = new UnrealPerceptionSessionTracker();
        UnrealPerceptionSink perceptionStore = mock(UnrealPerceptionSink.class);
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                commands,
                perceptionStore,
                metrics,
                localBroker,
                tracker,
                UnrealWorldSnapshotProvider.unavailable());
        WebSocketSession first = session("socket-a", new ArrayList<>());
        WebSocketSession second = session("socket-b", new ArrayList<>());
        handler.handleTextMessage(first, hello(0));
        handler.handleTextMessage(second, hello(0));

        handler.afterConnectionClosed(first, CloseStatus.NORMAL);
        verify(commands, never()).releaseSession("session-1");
        verify(perceptionStore, never()).releaseSession("session-1");

        handler.afterConnectionClosed(second, CloseStatus.NORMAL);
        verify(commands).releaseSession("session-1");
        verify(perceptionStore).releaseSession("session-1");
    }

    @Test
    void newRendererOwnsSessionLeaseBeforeWelcomeSendCanRaceOldClose() throws Exception {
        UnrealCommandDispatcher commands = mock(UnrealCommandDispatcher.class);
        when(commands.dispatch(any())).thenReturn(UnrealCommandDispatcher.DispatchResult.ACCEPTED);
        var localBroker = new UnrealEphemeralBroker(Clock.systemUTC());
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                commands,
                perception,
                metrics,
                localBroker,
                new UnrealPerceptionSessionTracker(),
                UnrealWorldSnapshotProvider.unavailable());
        WebSocketSession oldRenderer = session("desktop-renderer", new ArrayList<>());
        handler.handleTextMessage(oldRenderer, hello(0));
        WebSocketSession newRenderer = session("looking-glass-renderer", new ArrayList<>());
        doAnswer(invocation -> {
            handler.afterConnectionClosed(oldRenderer, CloseStatus.NORMAL);
            return null;
        }).when(newRenderer).sendMessage(any(TextMessage.class));

        handler.handleTextMessage(newRenderer, hello(0));

        verify(commands, never()).releaseSession("session-1");
        assertThat(localBroker.subscriberCount()).isEqualTo(1);
        assertThat(handler.connectionCount()).isEqualTo(1);
        handler.afterConnectionClosed(newRenderer, CloseStatus.NORMAL);
        verify(commands).releaseSession("session-1");
    }

    @Test
    void duplicateHelloCannotTearDownItsOwnActiveSession() throws Exception {
        UnrealCommandDispatcher commands = mock(UnrealCommandDispatcher.class);
        var localBroker = new UnrealEphemeralBroker(Clock.systemUTC());
        var messages = new ArrayList<TextMessage>();
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                commands,
                perception,
                metrics,
                localBroker);
        WebSocketSession renderer = session("desktop-renderer", messages);
        handler.handleTextMessage(renderer, hello(0));

        handler.handleTextMessage(renderer, hello(0));

        assertThat(parsed(messages.getLast()).path("payload").path("code").asText())
                .isEqualTo("hello_already_completed");
        assertThat(localBroker.subscriberCount()).isEqualTo(1);
        assertThat(handler.connectionCount()).isEqualTo(1);
        verify(commands, never()).releaseSession("session-1");
    }

    @Test
    void failedNewRendererHelloRollsBackOnlyItsLease() throws Exception {
        UnrealCommandDispatcher commands = mock(UnrealCommandDispatcher.class);
        var localBroker = new UnrealEphemeralBroker(Clock.systemUTC());
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                commands,
                perception,
                metrics,
                localBroker);
        WebSocketSession desktop = session("desktop-renderer", new ArrayList<>());
        handler.handleTextMessage(desktop, hello(0));
        WebSocketSession failedGo = session("looking-glass-renderer", new ArrayList<>());
        doAnswer(invocation -> {
            throw new IOException("display disconnected");
        }).when(failedGo).sendMessage(any(TextMessage.class));

        handler.handleTextMessage(failedGo, hello(0));

        assertThat(handler.connectionCount()).isEqualTo(1);
        assertThat(localBroker.subscriberCount()).isEqualTo(1);
        verify(commands, never()).releaseSession("session-1");
        handler.afterConnectionClosed(desktop, CloseStatus.NORMAL);
        verify(commands).releaseSession("session-1");
    }

    @Test
    void conflictingRendererIdentityIsRejectedBeforeItCanSubscribe() throws Exception {
        var localBroker = new UnrealEphemeralBroker(Clock.systemUTC());
        var clientSessions = new UnrealClientSessionRegistry();
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                perception,
                metrics,
                localBroker,
                new UnrealPerceptionSessionTracker(),
                UnrealWorldSnapshotProvider.unavailable(),
                UnrealActionCompletionPort.unavailable(),
                clientSessions);
        WebSocketSession desktop = session("desktop-renderer", new ArrayList<>());
        handler.handleTextMessage(desktop, hello(0));
        var conflictingMessages = new ArrayList<TextMessage>();
        WebSocketSession conflicting = session("foreign-renderer", conflictingMessages);
        TextMessage conflictingHello = envelope("client.hello", Map.of(
                "sessionId", "session-1",
                "worldId", "other-world",
                "installationId", "other-installation",
                "displayName", "Foreign",
                "lastSequence", 0));

        handler.handleTextMessage(conflicting, conflictingHello);

        assertThat(parsed(conflictingMessages.getLast()).path("payload").path("code").asText())
                .isEqualTo("incompatible_session_identity");
        assertThat(handler.connectionCount()).isEqualTo(1);
        assertThat(localBroker.subscriberCount()).isEqualTo(1);
        assertThat(clientSessions.find("session-1")).contains(
                new UnrealClientSessionRegistry.Binding(
                        "session-1", "gahyeon-home", "unreal-test-installation", "테스터"));
    }

    @Test
    void failedEphemeralSendRemovesOnlyThatRendererLeaseImmediately() throws Exception {
        UnrealCommandDispatcher commands = mock(UnrealCommandDispatcher.class);
        var localBroker = new UnrealEphemeralBroker(Clock.systemUTC());
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                commands,
                perception,
                metrics,
                localBroker);
        WebSocketSession failed = session("desktop-renderer", new ArrayList<>());
        var healthyMessages = new ArrayList<TextMessage>();
        WebSocketSession healthy = session("looking-glass-renderer", healthyMessages);
        handler.handleTextMessage(failed, hello(0));
        handler.handleTextMessage(healthy, hello(0));
        doAnswer(invocation -> {
            throw new IOException("display disconnected");
        }).when(failed).sendMessage(any(TextMessage.class));

        assertThat(localBroker.publish(
                "session-1", "speech.prepared", "unreal:g1:message", Map.of("generation", 1)))
                .isEqualTo(1);

        assertThat(parsed(healthyMessages.getLast()).path("type").asText())
                .isEqualTo("speech.prepared");
        assertThat(localBroker.subscriberCount()).isEqualTo(1);
        assertThat(handler.connectionCount()).isEqualTo(1);
        verify(commands, never()).releaseSession("session-1");
        verify(failed).close(CloseStatus.SERVER_ERROR);

        handler.afterConnectionClosed(healthy, CloseStatus.NORMAL);
        verify(commands).releaseSession("session-1");
    }

    @Test
    void failedLastEphemeralSubscriberReleasesSessionWithoutWaitingForCloseCallback()
            throws Exception {
        UnrealCommandDispatcher commands = mock(UnrealCommandDispatcher.class);
        var localBroker = new UnrealEphemeralBroker(Clock.systemUTC());
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                commands,
                perception,
                metrics,
                localBroker);
        WebSocketSession failed = session("desktop-renderer", new ArrayList<>());
        handler.handleTextMessage(failed, hello(0));
        doAnswer(invocation -> {
            throw new IOException("display disconnected");
        }).when(failed).sendMessage(any(TextMessage.class));

        assertThat(localBroker.publish(
                "session-1", "speech.prepared", "unreal:g1:message", Map.of("generation", 1)))
                .isZero();

        assertThat(localBroker.subscriberCount()).isZero();
        assertThat(handler.connectionCount()).isZero();
        verify(commands).releaseSession("session-1");
        verify(failed).close(CloseStatus.SERVER_ERROR);
        handler.afterConnectionClosed(failed, CloseStatus.SERVER_ERROR);
        verify(commands).releaseSession("session-1");
    }

    @Test
    void failedDurableReplayReleasesSessionWithoutWaitingForCloseCallback() throws Exception {
        UnrealCommandDispatcher commands = mock(UnrealCommandDispatcher.class);
        var localBroker = new UnrealEphemeralBroker(Clock.systemUTC());
        var clientSessions = new UnrealClientSessionRegistry();
        var replay = new UnrealEventReplayService(
                (sequence, limit) -> sequence < 4
                        ? List.of(event(4, "session-1", "conversation.completed"))
                        : List.of(),
                new UnrealEventMapper());
        var handler = new UnrealWebSocketHandler(
                json,
                replay,
                commands,
                perception,
                metrics,
                localBroker,
                new UnrealPerceptionSessionTracker(),
                UnrealWorldSnapshotProvider.unavailable(),
                UnrealActionCompletionPort.unavailable(),
                clientSessions);
        WebSocketSession failed = session("durable-replay-failed", new ArrayList<>());
        handler.handleTextMessage(failed, hello(0));
        doAnswer(invocation -> {
            throw new IOException("durable display disconnected");
        }).when(failed).sendMessage(any(TextMessage.class));

        handler.deliverEvents();

        assertThat(handler.connectionCount()).isZero();
        assertThat(localBroker.subscriberCount()).isZero();
        assertThat(clientSessions.connectionCount()).isZero();
        assertThat(clientSessions.openConnectionCount()).isZero();
        verify(commands).releaseSession("session-1");
        verify(failed).close(CloseStatus.SERVER_ERROR);

        handler.afterConnectionClosed(failed, CloseStatus.SERVER_ERROR);
        verify(commands).releaseSession("session-1");
    }

    @Test
    void slowDurableRendererDoesNotBlockHealthyRendererReplay() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var localBroker = new UnrealEphemeralBroker(Clock.systemUTC(), executor, 8);
            var replay = new UnrealEventReplayService(
                    (sequence, limit) -> sequence < 4
                            ? List.of(event(4, "session-1", "conversation.completed"))
                            : List.of(),
                    new UnrealEventMapper());
            var handler = new UnrealWebSocketHandler(
                    json,
                    replay,
                    command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                    perception,
                    metrics,
                    localBroker);
            WebSocketSession slow = session("slow-looking-glass", new ArrayList<>());
            WebSocketSession desktop = session("healthy-desktop", new ArrayList<>());
            handler.handleTextMessage(slow, hello(0));
            handler.handleTextMessage(desktop, hello(0));
            var slowStarted = new CountDownLatch(1);
            var releaseSlow = new CountDownLatch(1);
            var desktopDelivered = new CountDownLatch(1);
            doAnswer(invocation -> {
                slowStarted.countDown();
                releaseSlow.await();
                return null;
            }).when(slow).sendMessage(any(TextMessage.class));
            doAnswer(invocation -> {
                TextMessage message = invocation.getArgument(0);
                if ("cognition.response.completed".equals(parsed(message).path("type").asText())) {
                    desktopDelivered.countDown();
                }
                return null;
            }).when(desktop).sendMessage(any(TextMessage.class));

            handler.deliverEvents();

            assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(desktopDelivered.await(1, TimeUnit.SECONDS)).isTrue();
            releaseSlow.countDown();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void releasesSessionWhenBrokerAlreadyDroppedFailedSubscriber() throws Exception {
        UnrealCommandDispatcher commands = mock(UnrealCommandDispatcher.class);
        var localBroker = new UnrealEphemeralBroker(Clock.systemUTC());
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                commands,
                perception,
                metrics,
                localBroker);
        WebSocketSession session = session("socket-failed-send", new ArrayList<>());
        handler.handleTextMessage(session, hello(0));
        localBroker.unsubscribe(session.getId());

        handler.afterConnectionClosed(session, CloseStatus.SERVER_ERROR);

        verify(commands).releaseSession("session-1");
    }

    @Test
    void publishesAuthenticatedHelloIdentityForSiblingStreamingTransport() throws Exception {
        var clientSessions = new UnrealClientSessionRegistry();
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                perception,
                metrics,
                outbound,
                new UnrealPerceptionSessionTracker(),
                UnrealWorldSnapshotProvider.unavailable(),
                UnrealActionCompletionPort.unavailable(),
                clientSessions);
        WebSocketSession session = session("socket-stt-binding", new ArrayList<>());

        handler.handleTextMessage(session, hello(0));

        assertThat(clientSessions.find("session-1")).contains(
                new UnrealClientSessionRegistry.Binding(
                        "session-1", "gahyeon-home", "unreal-test-installation", "테스터"));
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertThat(clientSessions.find("session-1")).isEmpty();
    }

    @Test
    void rejectsRendererBeyondSessionCapacityWithoutAllocatingRuntimeState() throws Exception {
        var registry = new SimpleMeterRegistry();
        var runtimeMetrics = new UnrealRuntimeMetrics(registry);
        var clientSessions = new UnrealClientSessionRegistry(4, 1);
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                perception,
                runtimeMetrics,
                outbound,
                new UnrealPerceptionSessionTracker(),
                UnrealWorldSnapshotProvider.unavailable(),
                UnrealActionCompletionPort.unavailable(),
                clientSessions);
        var firstMessages = new ArrayList<TextMessage>();
        var rejectedMessages = new ArrayList<TextMessage>();
        WebSocketSession first = session("renderer-first", firstMessages);
        WebSocketSession rejected = session("renderer-rejected", rejectedMessages);

        handler.handleTextMessage(first, hello(0));
        handler.handleTextMessage(rejected, hello(0));

        assertThat(handler.connectionCount()).isEqualTo(1);
        assertThat(clientSessions.connectionCount()).isEqualTo(1);
        assertThat(parsed(rejectedMessages.getLast()).path("payload").path("code").asText())
                .isEqualTo("renderer_capacity");
        assertThat(registry.get("gahyeon.unreal.websocket.connection.rejected")
                .tag("reason", "session_capacity").counter().count()).isEqualTo(1);
    }

    @Test
    void rejectsOversizedHelloIdentityBeforeBindingTheConnection() throws Exception {
        var messages = new ArrayList<TextMessage>();
        var handler = handlerWithoutEvents();
        WebSocketSession session = session("socket-oversized-hello", messages);

        handler.handleTextMessage(session, envelope("client.hello", Map.of(
                "sessionId", "s".repeat(ConversationSessionId.MAXIMUM_EXTERNAL_ID_CHARACTERS + 1),
                "worldId", "gahyeon-home",
                "installationId", "unreal-test-installation",
                "displayName", "테스터",
                "lastSequence", 0)));

        assertThat(handler.connectionCount()).isZero();
        assertThat(parsed(messages.getLast()).path("type").asText()).isEqualTo("protocol.error");
        assertThat(parsed(messages.getLast()).path("payload").path("code").asText())
                .isEqualTo("invalid_field");
    }

    @Test
    void rejectsOversizedConversationTextBeforeCognitionAdmission() throws Exception {
        UnrealCommandDispatcher commands = mock(UnrealCommandDispatcher.class);
        var handler = new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                commands,
                perception,
                metrics,
                outbound);
        var messages = new ArrayList<TextMessage>();
        WebSocketSession session = session("socket-oversized-text", messages);
        handler.handleTextMessage(session, hello(0));

        handler.handleTextMessage(session, commandEnvelope(
                "interaction.text.submitted",
                Map.of("generation", 1, "text", "가".repeat(16_385))));

        verify(commands, never()).dispatch(any());
        assertThat(parsed(messages.getLast()).path("type").asText()).isEqualTo("protocol.error");
        assertThat(parsed(messages.getLast()).path("payload").path("code").asText())
                .isEqualTo("invalid_field");
    }

    @Test
    void rejectsNonTextEnvelopeIdentityWithoutCoercingJsonScalars() throws Exception {
        var messages = new ArrayList<TextMessage>();
        var handler = handlerWithoutEvents();
        WebSocketSession session = session("socket-non-text-message-id", messages);
        TextMessage input = new TextMessage(json.writeValueAsString(Map.of(
                "protocol", "gahyeon.unreal.v1",
                "schemaVersion", 1,
                "messageId", 42,
                "type", "client.ping",
                "sentAt", "2026-08-11T03:00:00Z",
                "correlationId", "strict-text",
                "delivery", "ephemeral",
                "payload", Map.of())));

        handler.handleTextMessage(session, input);

        JsonNode error = parsed(messages.getLast());
        assertThat(error.path("payload").path("code").asText()).isEqualTo("invalid_field");
        assertThat(error.path("payload").path("detail").asText()).contains("must be a string");
    }

    @Test
    void doesNotReflectOversizedCorrelationIdentityInProtocolErrors() throws Exception {
        var messages = new ArrayList<TextMessage>();
        var handler = handlerWithoutEvents();
        WebSocketSession session = session("socket-oversized-correlation", messages);
        TextMessage input = new TextMessage(json.writeValueAsString(Map.of(
                "protocol", "gahyeon.unreal.v1",
                "schemaVersion", 1,
                "messageId", "bounded-error",
                "type", "client.ping",
                "sentAt", "2026-08-11T03:00:00Z",
                "correlationId", "c".repeat(121),
                "delivery", "ephemeral",
                "payload", Map.of())));

        handler.handleTextMessage(session, input);

        JsonNode error = parsed(messages.getLast());
        assertThat(error.path("payload").path("code").asText()).isEqualTo("invalid_field");
        assertThat(error.path("correlationId").asText()).isEqualTo("protocol");
        assertThat(messages.getLast().getPayloadLength()).isLessThan(1_024);
    }

    private UnrealWebSocketHandler handlerWithoutEvents() {
        return new UnrealWebSocketHandler(
                json,
                new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper()),
                command -> UnrealCommandDispatcher.DispatchResult.ACCEPTED,
                perception,
                metrics,
                outbound);
    }

    private WebSocketSession session(String id, List<TextMessage> messages) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }

    private TextMessage hello(long lastSequence) throws Exception {
        return envelope("client.hello", Map.of(
                "sessionId", "session-1",
                "worldId", "gahyeon-home",
                "installationId", "unreal-test-installation",
                "displayName", "테스터",
                "lastSequence", lastSequence));
    }

    private TextMessage commandEnvelope(String type, Map<String, Object> payload) throws Exception {
        return envelope(type, "command", payload);
    }

    private TextMessage envelope(String type, Map<String, Object> payload) throws Exception {
        return envelope(type, "ephemeral", payload);
    }

    private TextMessage envelope(String type, String delivery, Map<String, Object> payload) throws Exception {
        return new TextMessage(json.writeValueAsString(Map.of(
                "protocol", "gahyeon.unreal.v1",
                "schemaVersion", 1,
                "messageId", "client-message-1",
                "type", type,
                "sentAt", "2026-08-11T03:00:00Z",
                "correlationId", "conversation-1",
                "delivery", delivery,
                "payload", payload)));
    }

    private JsonNode parsed(TextMessage message) throws Exception {
        return json.readTree(message.getPayload());
    }

    private GahyeonEvent event(long sequence, String sessionId, String type) {
        var session = new ConversationSessionId(sessionId);
        return new GahyeonEvent(2, "event-" + sequence, sequence, type,
                EventScope.session(sessionId), session, "conversation-1", Instant.EPOCH,
                Map.of("content", "안녕하세요."));
    }
}
