package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.adapters.unreal.protocol.UnrealEventMapper;
import com.gahyeonbot.core.event.EventScope;
import com.gahyeonbot.core.event.GahyeonEvent;
import com.gahyeonbot.core.session.ConversationSessionId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UnrealEventReplayServiceTest {
    @Test
    void filtersSessionAndWorldButAdvancesAcrossInvisibleEvents() {
        List<GahyeonEvent> stored = List.of(
                sessionEvent(11, "other-session"),
                worldEvent(12, "other-world"),
                sessionEvent(13, "session-1"),
                worldEvent(14, "gahyeon-home"),
                sessionEvent(15, "unreal:session-1"),
                sessionEvent(16, "desktop:session-1"));
        var service = new UnrealEventReplayService(
                (sequence, limit) -> stored.stream().filter(e -> e.sequence() > sequence).limit(limit).toList(),
                new UnrealEventMapper());

        var result = service.replay(
                new UnrealEventReplayService.UnrealSubscription("session-1", "gahyeon-home"),
                10,
                100);

        assertThat(result.messages()).extracting(message -> message.sequence())
                .containsExactly(13L, 14L, 15L);
        assertThat(result.scannedThrough()).isEqualTo(16);
    }

    @Test
    void retainsCursorWhenNoEventExists() {
        var service = new UnrealEventReplayService((sequence, limit) -> List.of(), new UnrealEventMapper());
        var result = service.replay(
                new UnrealEventReplayService.UnrealSubscription("session-1", "gahyeon-home"),
                27,
                100);
        assertThat(result.scannedThrough()).isEqualTo(27);
        assertThat(result.messages()).isEmpty();
    }

    private GahyeonEvent sessionEvent(long sequence, String sessionId) {
        var session = new ConversationSessionId(sessionId);
        return new GahyeonEvent(2, "event-" + sequence, sequence, "conversation.started",
                EventScope.session(sessionId), session, "correlation-1", Instant.EPOCH, Map.of());
    }

    private GahyeonEvent worldEvent(long sequence, String worldId) {
        return new GahyeonEvent(2, "event-" + sequence, sequence, "character.moved",
                EventScope.world(worldId), null, "correlation-1", Instant.EPOCH, Map.of());
    }
}
