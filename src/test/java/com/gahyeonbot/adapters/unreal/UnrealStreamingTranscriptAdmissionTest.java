package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.speech.StreamingTranscriptionPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnrealStreamingTranscriptAdmissionTest {
    private static final StreamingTranscriptionPort.StreamKey KEY =
            new StreamingTranscriptionPort.StreamKey("session-1", "stream-1", 4);

    @Test
    void routesPartialToPerceptionAndFinalToCognitionExactlyOnce() {
        var clients = new UnrealClientSessionRegistry();
        clients.bind("event-socket", new UnrealClientSessionRegistry.Binding(
                "session-1", "world-1", "install-1", "User"));
        var events = new ArrayList<UnrealPerceptionEvent>();
        var commands = new RecordingCommands();
        var admission = new UnrealStreamingTranscriptAdmission(
                clients,
                new UnrealPerceptionSessionTracker(),
                events::add,
                commands,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC));

        assertThat(admission.started(KEY)).isTrue();
        assertThat(admission.partial(KEY, "안녕", 0.8)).isTrue();
        assertThat(admission.ended(KEY)).isTrue();
        assertThat(admission.completed(KEY, "안녕하세요")).isTrue();
        assertThat(admission.completed(KEY, "중복")).isFalse();

        assertThat(commands.advanced).containsExactly("session-1:4");
        assertThat(commands.dispatched).hasSize(1);
        UnrealConversationCommand command = commands.dispatched.getFirst();
        assertThat(command.requestId()).isEqualTo("stt:4:stream-1");
        assertThat(command.installationId()).isEqualTo("install-1");
        assertThat(command.text()).isEqualTo("안녕하세요");
        assertThat(events).extracting(UnrealPerceptionEvent::type).containsExactly(
                "perception.voice.started",
                "perception.transcript.partial",
                "perception.voice.ended");
        assertThat(events.get(1).payload()).containsEntry("text", "안녕");
    }

    @Test
    void refusesUnboundSessionBeforeChangingLifecycle() {
        var admission = new UnrealStreamingTranscriptAdmission(
                new UnrealClientSessionRegistry(),
                new UnrealPerceptionSessionTracker(),
                event -> { throw new AssertionError("must not publish"); },
                command -> { throw new AssertionError("must not dispatch"); },
                Clock.systemUTC());

        assertThat(admission.started(KEY)).isFalse();
        assertThat(admission.partial(KEY, "untrusted", 1)).isFalse();
        assertThat(admission.completed(KEY, "untrusted")).isFalse();
    }

    @Test
    void rollsBackFinalWhenCognitionQueueRejectsIt() {
        var clients = new UnrealClientSessionRegistry();
        clients.bind("event-socket", new UnrealClientSessionRegistry.Binding(
                "session-1", "world-1", "install-1", "User"));
        var tracker = new UnrealPerceptionSessionTracker();
        var admission = new UnrealStreamingTranscriptAdmission(
                clients, tracker, event -> {}, command -> {
                    throw new IllegalStateException("queue full");
                }, Clock.systemUTC());
        assertThat(admission.started(KEY)).isTrue();
        assertThat(admission.ended(KEY)).isTrue();

        assertThat(admission.completed(KEY, "첫 시도")).isFalse();
        assertThat(tracker.admit("session-1", "perception.transcript.final", 4))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.ACCEPTED);
    }

    @Test
    void rollsBackFinalWhenCognitionExecutorReportsBackpressure() {
        var clients = new UnrealClientSessionRegistry();
        clients.bind("event-socket", new UnrealClientSessionRegistry.Binding(
                "session-1", "world-1", "install-1", "User"));
        var tracker = new UnrealPerceptionSessionTracker();
        var admission = new UnrealStreamingTranscriptAdmission(
                clients, tracker, event -> {},
                command -> UnrealCommandDispatcher.DispatchResult.BACKPRESSURE,
                Clock.systemUTC());
        assertThat(admission.started(KEY)).isTrue();
        assertThat(admission.ended(KEY)).isTrue();

        assertThat(admission.completed(KEY, "큐 포화 발화")).isFalse();
        assertThat(tracker.admit("session-1", "perception.transcript.final", 4))
                .isEqualTo(UnrealPerceptionSessionTracker.Admission.ACCEPTED);
    }

    @Test
    void cancelledProviderLifecycleRejectsCrossSocketLateTranscripts() {
        var clients = new UnrealClientSessionRegistry();
        clients.bind("event-socket", new UnrealClientSessionRegistry.Binding(
                "session-1", "world-1", "install-1", "User"));
        var admission = new UnrealStreamingTranscriptAdmission(
                clients, new UnrealPerceptionSessionTracker(), event -> {},
                command -> { throw new AssertionError("cancelled final must not dispatch"); },
                Clock.systemUTC());
        assertThat(admission.started(KEY)).isTrue();

        admission.failed(KEY);

        assertThat(admission.partial(KEY, "late partial", 1)).isFalse();
        assertThat(admission.completed(KEY, "late final")).isFalse();
    }

    private static final class RecordingCommands implements UnrealCommandDispatcher {
        private final List<String> advanced = new ArrayList<>();
        private final List<UnrealConversationCommand> dispatched = new ArrayList<>();

        @Override
        public DispatchResult dispatch(UnrealConversationCommand command) {
            dispatched.add(command);
            return DispatchResult.ACCEPTED;
        }

        @Override
        public void advanceGeneration(String sessionId, long generation) {
            advanced.add(sessionId + ":" + generation);
        }
    }
}
