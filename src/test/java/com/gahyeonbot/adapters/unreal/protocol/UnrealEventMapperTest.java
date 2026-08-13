package com.gahyeonbot.adapters.unreal.protocol;

import com.gahyeonbot.core.event.EventScope;
import com.gahyeonbot.core.event.GahyeonEvent;
import com.gahyeonbot.core.session.ConversationSessionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UnrealEventMapperTest {
    private final UnrealEventMapper mapper = new UnrealEventMapper();

    @Test
    void mapsConversationCompletionWithoutInventingAnIdleTransition() {
        var event = sessionEvent(7, "conversation.completed", Map.of(
                "content", "안녕하세요.", "durationMillis", 900));

        UnrealEnvelope envelope = mapper.map(event);

        assertThat(envelope.type()).isEqualTo("cognition.response.completed");
        assertThat(envelope.sequence()).isEqualTo(7);
        assertThat(envelope.payload()).containsEntry("content", "안녕하세요.");
    }

    @Test
    void promotesLegacyExpressionToAnEmotionDimension() {
        var event = worldEvent(8, "avatar.expression", Map.of(
                "revision", 3, "expression", "curiosity", "intensity", 0.7));

        UnrealEnvelope envelope = mapper.map(event);

        assertThat(envelope.type()).isEqualTo("emotion.target");
        assertThat(envelope.payload()).containsEntry("revision", 3);
        assertThat(envelope.payload().get("dimensions"))
                .isEqualTo(Map.of("curiosity", 0.7));
    }

    @Test
    void preservesUnknownSemanticEventForForwardCompatibility() {
        var event = worldEvent(9, "future.semantic.event", Map.of("value", 1));
        assertThat(mapper.map(event).type()).isEqualTo("future.semantic.event");
        assertThat(mapper.map(event).payload()).containsEntry("value", 1);
    }

    @Test
    void mapsDurableWorldStateChangeToAuthoritativeSnapshot() {
        var event = worldEvent(10, "world.state.changed", Map.of(
                "worldId", "gahyeon-home", "revision", 4,
                "currentRoom", "workspace"));

        UnrealEnvelope envelope = mapper.map(event);

        assertThat(envelope.type()).isEqualTo("world.snapshot");
        assertThat(envelope.delivery()).isEqualTo("durable");
        assertThat(envelope.payload()).containsEntry("revision", 4);
    }

    @Test
    void restoresGenerationFromAReplayableUnrealCorrelation() {
        var session = new ConversationSessionId("session-1");
        var event = new GahyeonEvent(2, "event-10", 10, "conversation.completed",
                EventScope.session(session.value()), session,
                "unreal:g12:client-message-1", Instant.EPOCH, Map.of("content", "늦은 응답"));

        assertThat(mapper.map(event).payload()).containsEntry("generation", 12L);
    }

    @Test
    void mapsSupersededWorkAsCancellationRatherThanProviderFailure() {
        var session = new ConversationSessionId("session-1");
        var event = new GahyeonEvent(2, "event-11", 11, "conversation.cancelled",
                EventScope.session(session.value()), session,
                "unreal:g4:old-message", Instant.EPOCH, Map.of("message", "interrupted"));

        UnrealEnvelope envelope = mapper.map(event);

        assertThat(envelope.type()).isEqualTo("cognition.request.cancelled");
        assertThat(envelope.payload()).containsEntry("generation", 4L);
    }

    @Test
    void preservesCoreCharacterStateAndAddsTheUnrealGeneration() {
        var session = new ConversationSessionId("session-1");
        var event = new GahyeonEvent(2, "event-state", 12, "character.state.target",
                EventScope.session(session.value()), session,
                "unreal:g9:client-message", Instant.EPOCH,
                Map.of("state", "thinking", "priority", 50));

        UnrealEnvelope envelope = mapper.map(event);

        assertThat(envelope.type()).isEqualTo("character.state.target");
        assertThat(envelope.payload()).containsEntry("generation", 9L)
                .containsEntry("state", "thinking")
                .containsEntry("priority", 50);
    }

    private GahyeonEvent sessionEvent(long sequence, String type, Map<String, Object> payload) {
        var session = new ConversationSessionId("session-1");
        return new GahyeonEvent(2, "event-" + sequence, sequence, type,
                EventScope.session(session.value()), session, "correlation-1", Instant.EPOCH, payload);
    }

    private GahyeonEvent worldEvent(long sequence, String type, Map<String, Object> payload) {
        return new GahyeonEvent(2, "event-" + sequence, sequence, type,
                EventScope.world("gahyeon-home"), null, "correlation-1", Instant.EPOCH, payload);
    }
}
