package com.gahyeonbot.adapters.unreal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.speech.StreamingTranscriptionPort;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnrealStreamingSttWebSocketHandlerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void transportsPcmAndProviderResultsUsingV1Schema() throws Exception {
        FakeProvider provider = new FakeProvider();
        var handler = new UnrealStreamingSttWebSocketHandler(json, provider);
        var sent = new ArrayList<WebSocketMessage<?>>();
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage(start("session-1", "stream-1", 3)));
        handler.handleMessage(session, new BinaryMessage(frame(0)));
        provider.listener.onPartial(0, "안녕", 0.75);
        handler.handleMessage(session, new TextMessage(end("session-1", "stream-1", 3, 0)));
        provider.listener.onFinal(1, "안녕하세요", "ko-KR");

        assertThat(provider.sequences).containsExactly(0L);
        assertThat(provider.finished).isTrue();
        assertThat(sent).hasSize(2);
        JsonNode partial = json.readTree(((TextMessage) sent.get(0)).getPayload());
        JsonNode completed = json.readTree(((TextMessage) sent.get(1)).getPayload());
        assertThat(partial.path("type").asText()).isEqualTo("stt.transcript.partial");
        assertThat(partial.path("stability").asDouble()).isEqualTo(0.75);
        assertThat(completed.path("type").asText()).isEqualTo("stt.transcript.final");
        assertThat(completed.path("generation").asLong()).isEqualTo(3);
    }

    @Test
    void recordsFirstPartialEndToEndDurationAndBoundedResultLabels() throws Exception {
        FakeProvider provider = new FakeProvider();
        var registry = new SimpleMeterRegistry();
        var clock = new AtomicLong(1_000);
        var handler = new UnrealStreamingSttWebSocketHandler(
                json, provider, null, new UnrealRuntimeMetrics(registry), clock::get);
        WebSocketSession session = session(new ArrayList<>());
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage(start("session-1", "stream-1", 3)));
        handler.handleMessage(session, new BinaryMessage(frame(0)));
        clock.set(1_125);
        provider.listener.onPartial(0, "안녕", 0.75);
        clock.set(1_300);
        handler.handleMessage(session, new TextMessage(end("session-1", "stream-1", 3, 0)));
        clock.set(1_450);
        provider.listener.onFinal(1, "안녕하세요", "ko-KR");

        assertThat(registry.timer("gahyeon.unreal.stt.streaming.first.partial").count())
                .isEqualTo(1);
        assertThat(registry.timer("gahyeon.unreal.stt.streaming.first.partial")
                .totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isEqualTo(125);
        assertThat(registry.timer("gahyeon.unreal.stt.streaming.duration", "result", "final")
                .totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isEqualTo(450);
        assertThat(registry.counter("gahyeon.unreal.stt.streaming.events", "type", "partial")
                .count()).isEqualTo(1);
    }

    @Test
    void recordsBackpressureAsFailureAndTerminalDuration() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.offerResult = StreamingTranscriptionPort.OfferResult.BACKPRESSURE;
        var registry = new SimpleMeterRegistry();
        var clock = new AtomicLong(2_000);
        var handler = new UnrealStreamingSttWebSocketHandler(
                json, provider, null, new UnrealRuntimeMetrics(registry), clock::get);
        WebSocketSession session = session(new ArrayList<>());
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage(start("session-1", "stream-1", 3)));

        clock.set(2_090);
        handler.handleMessage(session, new BinaryMessage(frame(0)));

        assertThat(registry.counter("gahyeon.unreal.stt.streaming.failures",
                "code", "backpressure").count()).isEqualTo(1);
        assertThat(registry.timer("gahyeon.unreal.stt.streaming.duration",
                "result", "backpressure").count()).isEqualTo(1);
    }

    @Test
    void providerCallbackQueuesResultsWithoutWaitingForSlowSocketAndPreservesOrder() throws Exception {
        FakeProvider provider = new FakeProvider();
        var scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(mock(ScheduledFuture.class));
        var drain = new AtomicReference<Runnable>();
        var sent = new ArrayList<WebSocketMessage<?>>();
        var handler = new UnrealStreamingSttWebSocketHandler(
                json, provider, null, null, System::nanoTime, scheduler,
                120, 32, 10, drain::set, 4);
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage(start("session-1", "stream-1", 3)));

        provider.listener.onPartial(0, "첫째", 0.8);
        provider.listener.onPartial(1, "둘째", 0.9);

        assertThat(sent).isEmpty();
        assertThat(drain.get()).isNotNull();
        drain.get().run();
        assertThat(sent).hasSize(2);
        assertThat(json.readTree(((TextMessage) sent.get(0)).getPayload()).path("text").asText())
                .isEqualTo("첫째");
        assertThat(json.readTree(((TextMessage) sent.get(1)).getPayload()).path("text").asText())
                .isEqualTo("둘째");
    }

    @Test
    void providerCallbackCannotInvertDeadlineAndStateMachineLocks() throws Exception {
        FakeProvider provider = new FakeProvider();
        var scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> scheduledFuture = mock(ScheduledFuture.class);
        var scheduleCalls = new AtomicInteger();
        var deadlineMonitorHeld = new CountDownLatch(1);
        var callbackCompleted = new CountDownLatch(1);
        var callbackCompletedWhileDeadlineMonitorHeld = new AtomicReference<Boolean>();
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> {
                    if (scheduleCalls.incrementAndGet() == 2) {
                        // armDeadline invokes the scheduler while holding the Connection monitor.
                        // A provider callback must still reach serialization without needing that
                        // monitor or retaining the state-machine monitor in a lock cycle.
                        deadlineMonitorHeld.countDown();
                        try {
                            callbackCompletedWhileDeadlineMonitorHeld.set(
                                    callbackCompleted.await(2, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("deadline admission interrupted", interrupted);
                        }
                    }
                    return scheduledFuture;
                });
        var handler = new UnrealStreamingSttWebSocketHandler(
                json, provider, null, null, System::nanoTime, scheduler,
                120, 32, 10, Runnable::run, 4);
        WebSocketSession session = session(new ArrayList<>());
        handler.afterConnectionEstablished(session);
        var workers = Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "streaming-stt-lock-regression");
            thread.setDaemon(true);
            return thread;
        });

        try {
            var start = workers.submit(() -> {
                handler.handleMessage(session, new TextMessage(
                        start("session-1", "stream-1", 3)));
                return null;
            });
            assertThat(deadlineMonitorHeld.await(1, TimeUnit.SECONDS)).isTrue();
            var callback = workers.submit(() -> {
                provider.listener.onPartial(0, "교착 방지", 0.9);
                callbackCompleted.countDown();
                return null;
            });

            // A regression fails within seconds rather than retaining Gradle's Test worker.
            assertThat(start.get(2, TimeUnit.SECONDS)).isNull();
            assertThat(callback.get(2, TimeUnit.SECONDS)).isNull();
            assertThat(callbackCompletedWhileDeadlineMonitorHeld).hasValue(true);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void isolatesConnectionWhenItsSlowSocketExhaustsOutboundQueue() throws Exception {
        FakeProvider provider = new FakeProvider();
        var scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(mock(ScheduledFuture.class));
        var registry = new SimpleMeterRegistry();
        var handler = new UnrealStreamingSttWebSocketHandler(
                json, provider, null, new UnrealRuntimeMetrics(registry), System::nanoTime,
                scheduler, 120, 32, 10, ignored -> {}, 2);
        WebSocketSession session = session(new ArrayList<>());
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage(start("session-1", "stream-1", 3)));

        provider.listener.onPartial(0, "첫째", 0.8);
        provider.listener.onPartial(1, "둘째", 0.8);
        provider.listener.onPartial(2, "셋째", 0.8);

        assertThat(provider.cancelled).isEqualTo(
                StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertThat(registry.counter(
                "gahyeon.unreal.stt.streaming.outbound.detached",
                "reason", "queue_full").count()).isEqualTo(1);
    }

    @Test
    void transportDeadlineReclaimsHalfOpenProviderSession() throws Exception {
        FakeProvider provider = new FakeProvider();
        var scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        var scheduled = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    scheduled.set(invocation.getArgument(0));
                    return future;
                });
        var registry = new SimpleMeterRegistry();
        var sent = new ArrayList<WebSocketMessage<?>>();
        var handler = new UnrealStreamingSttWebSocketHandler(
                json, provider, null, new UnrealRuntimeMetrics(registry), System::nanoTime,
                scheduler, 5);
        WebSocketSession session = session(sent);
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage(start("session-1", "stream-1", 3)));

        scheduled.get().run();
        provider.listener.onPartial(0, "늦은 결과", 0.8);

        assertThat(provider.cancelled).isEqualTo(StreamingTranscriptionPort.CancelReason.TIMEOUT);
        assertThat(sent).hasSize(1);
        assertThat(json.readTree(((TextMessage) sent.getFirst()).getPayload())
                .path("code").asText()).isEqualTo("provider_timeout");
        assertThat(registry.counter("gahyeon.unreal.stt.streaming.failures",
                "code", "provider_timeout").count()).isEqualTo(1);
        assertThat(registry.timer("gahyeon.unreal.stt.streaming.duration",
                "result", "provider_timeout").count()).isEqualTo(1);
    }

    @Test
    void connectionCapacityRejectsOnlyNewSocketAndGaugeIsIdempotent() throws Exception {
        FakeProvider provider = new FakeProvider();
        var scheduler = mock(ScheduledExecutorService.class);
        var registry = new SimpleMeterRegistry();
        var metrics = new UnrealRuntimeMetrics(registry);
        var handler = new UnrealStreamingSttWebSocketHandler(
                json, provider, null, metrics, System::nanoTime, scheduler, 5, 1);
        WebSocketSession admitted = session("socket-1", new ArrayList<>());
        WebSocketSession rejected = session("socket-2", new ArrayList<>());

        handler.afterConnectionEstablished(admitted);
        handler.afterConnectionEstablished(rejected);

        verify(rejected).close(new CloseStatus(1013, "streaming_stt_capacity"));
        assertThat(registry.get("gahyeon.unreal.stt.streaming.connections").gauge().value())
                .isEqualTo(1);
        assertThat(registry.counter("gahyeon.unreal.stt.streaming.connection.rejected",
                "reason", "capacity").count()).isEqualTo(1);

        handler.afterConnectionClosed(admitted, CloseStatus.NORMAL);
        handler.afterConnectionClosed(admitted, CloseStatus.NORMAL);
        assertThat(registry.get("gahyeon.unreal.stt.streaming.connections").gauge().value())
                .isZero();
    }

    @Test
    void initialStartDeadlineReclaimsIdleSocketButCannotCloseStartedStream() throws Exception {
        FakeProvider provider = new FakeProvider();
        var scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> future = mock(ScheduledFuture.class);
        var tasks = new ArrayList<Runnable>();
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    tasks.add(invocation.getArgument(0));
                    return future;
                });
        var registry = new SimpleMeterRegistry();
        var handler = new UnrealStreamingSttWebSocketHandler(
                json, provider, null, new UnrealRuntimeMetrics(registry), System::nanoTime,
                scheduler, 5, 2, 2);
        WebSocketSession started = session("started", new ArrayList<>());
        handler.afterConnectionEstablished(started);
        handler.handleMessage(started, new TextMessage(start("session-1", "stream-1", 3)));

        tasks.get(0).run();

        verify(started, never()).close(any(CloseStatus.class));
        assertThat(provider.cancelled).isNull();

        WebSocketSession idle = session("idle", new ArrayList<>());
        handler.afterConnectionEstablished(idle);
        tasks.get(2).run();

        verify(idle).close(CloseStatus.POLICY_VIOLATION);
        assertThat(registry.counter("gahyeon.unreal.stt.streaming.failures",
                "code", "initial_timeout").count()).isEqualTo(1);
        assertThat(registry.get("gahyeon.unreal.stt.streaming.connections").gauge().value())
                .isEqualTo(1);
    }

    @Test
    void admitsTrustedProviderFinalDirectlyIntoCoreCognition() throws Exception {
        FakeProvider provider = new FakeProvider();
        var clients = new UnrealClientSessionRegistry();
        clients.bind("event-socket", new UnrealClientSessionRegistry.Binding(
                "session-1", "world-1", "install-1", "User"));
        var dispatched = new ArrayList<UnrealConversationCommand>();
        var admission = new UnrealStreamingTranscriptAdmission(
                clients,
                new UnrealPerceptionSessionTracker(),
                event -> {},
                command -> {
                    dispatched.add(command);
                    return UnrealCommandDispatcher.DispatchResult.ACCEPTED;
                },
                Clock.systemUTC());
        var handler = new UnrealStreamingSttWebSocketHandler(json, provider, admission);
        WebSocketSession session = session(new ArrayList<>());
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage(start("session-1", "stream-1", 3)));
        handler.handleMessage(session, new BinaryMessage(frame(0)));
        provider.listener.onPartial(0, "안녕", 0.75);
        handler.handleMessage(session, new TextMessage(end("session-1", "stream-1", 3, 0)));
        provider.listener.onFinal(1, "최종 발화", "ko-KR");

        assertThat(dispatched).hasSize(1);
        assertThat(dispatched.getFirst().requestId()).isEqualTo("stt:3:stream-1");
        assertThat(dispatched.getFirst().text()).isEqualTo("최종 발화");
    }

    @Test
    void bindsOneCoreSessionToAConnection() throws Exception {
        FakeProvider provider = new FakeProvider();
        var handler = new UnrealStreamingSttWebSocketHandler(json, provider);
        WebSocketSession session = session(new ArrayList<>());
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage(start("session-1", "stream-1", 3)));

        handler.handleMessage(session, new TextMessage(start("session-2", "stream-2", 4)));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertThat(provider.cancelled).isEqualTo(
                StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
    }

    @Test
    void preservesCaptureAbortReasonAndReleasesProviderSession() throws Exception {
        FakeProvider provider = new FakeProvider();
        UnrealStreamingTranscriptAdmission admission = mock(
                UnrealStreamingTranscriptAdmission.class);
        when(admission.started(any())).thenReturn(true);
        var registry = new SimpleMeterRegistry();
        var handler = new UnrealStreamingSttWebSocketHandler(
                json, provider, admission, new UnrealRuntimeMetrics(registry), System::nanoTime);
        WebSocketSession session = session(new ArrayList<>());
        handler.afterConnectionEstablished(session);
        handler.handleMessage(session, new TextMessage(start("session-1", "stream-1", 3)));

        handler.handleMessage(session, new TextMessage(cancel(
                "session-1", "stream-1", 3, "capture_error")));

        assertThat(provider.cancelled).isEqualTo(
                StreamingTranscriptionPort.CancelReason.CAPTURE_ERROR);
        verify(admission).failed(new StreamingTranscriptionPort.StreamKey(
                "session-1", "stream-1", 3));
        assertThat(registry.counter("gahyeon.unreal.stt.streaming.events",
                "type", "cancelled").count()).isEqualTo(1);
        assertThat(registry.timer("gahyeon.unreal.stt.streaming.duration",
                "result", "cancelled").count()).isEqualTo(1);
    }

    @Test
    void rejectsUnexpectedJsonPropertiesRatherThanIgnoringFormatDrift() throws Exception {
        FakeProvider provider = new FakeProvider();
        var handler = new UnrealStreamingSttWebSocketHandler(json, provider);
        WebSocketSession session = session(new ArrayList<>());
        handler.afterConnectionEstablished(session);
        String invalid = start("session-1", "stream-1", 3)
                .replace("\"format\":", "\"unexpected\":true,\"format\":");

        handler.handleMessage(session, new TextMessage(invalid));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertThat(provider.openCount).isZero();
    }

    @Test
    void rejectsFractionalSchemaVersionWithoutOpeningProviderSession() throws Exception {
        FakeProvider provider = new FakeProvider();
        var handler = new UnrealStreamingSttWebSocketHandler(json, provider);
        WebSocketSession session = session(new ArrayList<>());
        handler.afterConnectionEstablished(session);
        String invalid = start("session-1", "stream-1", 3)
                .replace("\"schemaVersion\":1", "\"schemaVersion\":1.5");

        handler.handleMessage(session, new TextMessage(invalid));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertThat(provider.openCount).isZero();
    }

    private WebSocketSession session(List<WebSocketMessage<?>> sent) throws Exception {
        return session("socket-1", sent);
    }

    private WebSocketSession session(String id, List<WebSocketMessage<?>> sent) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return null;
        }).when(session).sendMessage(any());
        return session;
    }

    private static String start(String sessionId, String streamId, long generation) {
        return """
                {"schemaVersion":1,"type":"stt.stream.start","sessionId":"%s",\
                "streamId":"%s","generation":%d,"observedAtMs":10,"format":{\
                "encoding":"float32le","sampleRate":16000,"channels":1,"framesPerChunk":320}}
                """.formatted(sessionId, streamId, generation);
    }

    private static String end(String sessionId, String streamId, long generation, long last) {
        return """
                {"schemaVersion":1,"type":"stt.stream.end","sessionId":"%s",\
                "streamId":"%s","generation":%d,"observedAtMs":20,"lastAudioSequence":%d}
                """.formatted(sessionId, streamId, generation, last);
    }

    private static String cancel(
            String sessionId, String streamId, long generation, String reason) {
        return """
                {"schemaVersion":1,"type":"stt.stream.cancel","sessionId":"%s",\
                "streamId":"%s","generation":%d,"reason":"%s"}
                """.formatted(sessionId, streamId, generation, reason);
    }

    private static byte[] frame(long sequence) {
        return ByteBuffer.allocate(Long.BYTES + 320 * Float.BYTES)
                .order(ByteOrder.BIG_ENDIAN).putLong(sequence).array();
    }

    private static final class FakeProvider implements StreamingTranscriptionPort {
        private int openCount;
        private ResultListener listener;
        private final List<Long> sequences = new ArrayList<>();
        private boolean finished;
        private CancelReason cancelled;
        private OfferResult offerResult = OfferResult.ACCEPTED;

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public Session open(StartRequest request, ResultListener listener) {
            openCount++;
            this.listener = listener;
            return new Session() {
                @Override
                public OfferResult offer(AudioChunk chunk) {
                    sequences.add(chunk.sequence());
                    return offerResult;
                }

                @Override
                public void finish() {
                    finished = true;
                }

                @Override
                public void cancel(CancelReason reason) {
                    cancelled = reason;
                }
            };
        }
    }
}
