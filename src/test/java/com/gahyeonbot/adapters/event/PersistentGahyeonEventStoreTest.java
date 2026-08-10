package com.gahyeonbot.adapters.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.core.event.GahyeonEventDraft;
import com.gahyeonbot.core.event.EventScopeType;
import com.gahyeonbot.core.session.ConversationSessionId;
import com.gahyeonbot.repository.GahyeonEventRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PersistentGahyeonEventStoreTest {
    @Autowired GahyeonEventRecordRepository repository;

    @Test
    void persistsVersionedOrderedEventsAndSupportsResumeCursor() {
        var store = new PersistentGahyeonEventStore(repository, new ObjectMapper());
        var session = new ConversationSessionId("desktop-session");

        var first = store.publish(new GahyeonEventDraft(
                "conversation.started", session, "request-1", Map.of("source", "desktop")));
        var second = store.publish(new GahyeonEventDraft(
                "avatar.expression.changed", session, "request-1",
                Map.of("expression", "happy", "intensity", 0.7)));

        assertThat(first.schemaVersion()).isEqualTo(2);
        assertThat(second.sequence()).isGreaterThan(first.sequence());
        assertThat(store.after(first.sequence(), 100))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.sequence()).isEqualTo(second.sequence());
                    assertThat(event.type()).isEqualTo("avatar.expression.changed");
                    assertThat(event.payload()).containsEntry("expression", "happy");
                });
    }

    @Test
    void persistsWorldScopedEventsWithoutFakeConversationSession() {
        var store = new PersistentGahyeonEventStore(repository, new ObjectMapper());

        var event = store.publish(GahyeonEventDraft.world(
                "world.state.changed",
                "gahyeon-home",
                "world-revision-2",
                Map.of("room", "workspace")));

        assertThat(event.scope().type()).isEqualTo(EventScopeType.WORLD);
        assertThat(event.scope().id()).isEqualTo("gahyeon-home");
        assertThat(event.sessionId()).isNull();
    }
}
