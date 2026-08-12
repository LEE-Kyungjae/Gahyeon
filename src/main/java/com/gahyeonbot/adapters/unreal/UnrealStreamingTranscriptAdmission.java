package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.speech.StreamingTranscriptionPort;
import com.gahyeonbot.core.session.ConversationModality;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Routes trusted provider transcripts directly into Behavior and Cognition exactly once. */
public final class UnrealStreamingTranscriptAdmission {
    private final UnrealClientSessionRegistry clients;
    private final UnrealPerceptionSessionTracker lifecycle;
    private final UnrealPerceptionSink perception;
    private final UnrealCommandDispatcher commands;
    private final Clock clock;

    public UnrealStreamingTranscriptAdmission(
            UnrealClientSessionRegistry clients,
            UnrealPerceptionSessionTracker lifecycle,
            UnrealPerceptionSink perception,
            UnrealCommandDispatcher commands,
            Clock clock) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.perception = Objects.requireNonNull(perception, "perception");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean started(StreamingTranscriptionPort.StreamKey key) {
        var binding = clients.find(key.sessionId()).orElse(null);
        if (binding == null) return false;
        var admission = lifecycle.admit(
                key.sessionId(), "perception.voice.started", key.generation());
        if (admission != UnrealPerceptionSessionTracker.Admission.ACCEPTED) return false;
        commands.advanceGeneration(key.sessionId(), key.generation());
        acceptPerception(binding, key, "perception.voice.started", Map.of());
        return true;
    }

    public boolean partial(
            StreamingTranscriptionPort.StreamKey key,
            String text,
            double stability) {
        var binding = clients.find(key.sessionId()).orElse(null);
        if (binding == null || lifecycle.admit(
                key.sessionId(), "perception.transcript.partial", key.generation())
                != UnrealPerceptionSessionTracker.Admission.ACCEPTED) return false;
        acceptPerception(binding, key, "perception.transcript.partial",
                Map.of("text", text, "stability", stability));
        return true;
    }

    public boolean ended(StreamingTranscriptionPort.StreamKey key) {
        var binding = clients.find(key.sessionId()).orElse(null);
        if (binding == null || lifecycle.admit(
                key.sessionId(), "perception.voice.ended", key.generation())
                != UnrealPerceptionSessionTracker.Admission.ACCEPTED) return false;
        acceptPerception(binding, key, "perception.voice.ended", Map.of());
        return true;
    }

    public boolean completed(StreamingTranscriptionPort.StreamKey key, String text) {
        var binding = clients.find(key.sessionId()).orElse(null);
        if (binding == null || lifecycle.admit(
                key.sessionId(), "perception.transcript.final", key.generation())
                != UnrealPerceptionSessionTracker.Admission.ACCEPTED) return false;
        String requestId = "stt:" + key.generation() + ":" + key.streamId();
        try {
            UnrealCommandDispatcher.DispatchResult result = commands.dispatch(
                    new UnrealConversationCommand(
                            requestId,
                            key.sessionId(),
                            binding.installationId(),
                            binding.displayName(),
                            ConversationModality.VOICE,
                            key.generation(),
                            text));
            if (result == UnrealCommandDispatcher.DispatchResult.STALE
                    || result == UnrealCommandDispatcher.DispatchResult.BACKPRESSURE) {
                lifecycle.rollbackFinal(key.sessionId(), key.generation());
                return false;
            }
            return true;
        } catch (RuntimeException error) {
            lifecycle.rollbackFinal(key.sessionId(), key.generation());
            return false;
        }
    }

    public void failed(StreamingTranscriptionPort.StreamKey key) {
        lifecycle.abortVoice(key.sessionId(), key.generation());
    }

    private void acceptPerception(
            UnrealClientSessionRegistry.Binding binding,
            StreamingTranscriptionPort.StreamKey key,
            String type,
            Map<String, Object> payload) {
        perception.accept(new UnrealPerceptionEvent(
                type,
                key.sessionId(),
                binding.worldId(),
                key.generation(),
                Instant.now(clock),
                payload));
    }
}
