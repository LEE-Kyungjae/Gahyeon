package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.event.GahyeonEventQuery;
import com.gahyeonbot.core.event.EventScope;
import com.gahyeonbot.core.event.EventScopeType;
import com.gahyeonbot.core.event.GahyeonEvent;
import com.gahyeonbot.core.session.ClientSource;
import com.gahyeonbot.core.session.ConversationSessionId;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DesktopEventStreamServiceTest {
    @Test
    void newerConversationCancelsThePreviousGenerationWithoutRemovingItself() {
        var streams = new DesktopEventStreamService(mock(GahyeonEventQuery.class), 128, 4);

        String first = streams.beginConversation("session-1");
        String second = streams.beginConversation("session-1");

        assertThat(streams.isCurrentConversation("session-1", first)).isFalse();
        assertThat(streams.isCurrentConversation("session-1", second)).isTrue();
        streams.finishConversation("session-1", first);
        assertThat(streams.isCurrentConversation("session-1", second)).isTrue();
        streams.finishConversation("session-1", second);
        assertThat(streams.isCurrentConversation("session-1", second)).isFalse();

        String third = streams.beginConversation("session-1");
        streams.cancelConversation("session-1");
        assertThat(streams.isCurrentConversation("session-1", third)).isFalse();
    }

    @Test
    void boundsSubscriptionsPerSessionAndGlobally() {
        var streams = new DesktopEventStreamService(mock(GahyeonEventQuery.class), 4, 2);
        streams.subscribe("session-1", "world-1", 0);
        streams.subscribe("session-1", "world-1", 0);
        assertThat(streams.activeSubscriptions()).isEqualTo(2);

        assertThatThrownBy(() -> streams.subscribe("session-1", "world-1", 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429 TOO_MANY_REQUESTS");
        streams.subscribe("session-2", "world-1", 0);
        streams.subscribe("session-3", "world-2", 0);
        assertThatThrownBy(() -> streams.subscribe("session-4", "world-2", 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429 TOO_MANY_REQUESTS");
        assertThat(streams.activeSubscriptions()).isEqualTo(4);
    }

    @Test
    void concurrentAdmissionCannotRacePastThePerSessionLimit() throws Exception {
        var streams = new DesktopEventStreamService(mock(GahyeonEventQuery.class), 16, 2);
        var admitted = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 16; index++) {
                executor.submit(() -> {
                    try {
                        streams.subscribe("same-session", "world-1", 0);
                        admitted.incrementAndGet();
                    } catch (ResponseStatusException ignored) {
                        // Expected isolation after the first two admissions.
                    }
                });
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(admitted).hasValue(2);
        assertThat(streams.activeSubscriptions()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidSubscriptionLimits() {
        assertThatThrownBy(() -> new DesktopEventStreamService(
                mock(GahyeonEventQuery.class), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DesktopEventStreamService(
                mock(GahyeonEventQuery.class), 2, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnboundedSessionIdentityBeforeAllocatingAnEmitter() {
        var streams = new DesktopEventStreamService(mock(GahyeonEventQuery.class), 4, 2);
        assertThatThrownBy(() -> streams.subscribe("s".repeat(181), "world-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> streams.subscribe("session-1", "w".repeat(181), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(streams.activeSubscriptions()).isZero();
    }

    @Test
    void deliversOnlyTheSubscribedWorldWhileRetainingSystemAndMatchingSessionEvents() {
        GahyeonEventQuery events = mock(GahyeonEventQuery.class);
        ConversationSessionId sessionId = ConversationSessionId.fromExternal(
                ClientSource.DESKTOP, "session-1");
        when(events.after(0, 100)).thenReturn(List.of(
                event(1, EventScope.world("another-world"), null),
                event(2, EventScope.world("world-1"), null),
                event(3, new EventScope(EventScopeType.SYSTEM, "gahyeon"), null),
                event(4, EventScope.session(sessionId.value()), sessionId),
                event(5, EventScope.session("another-session"), sessionId)));
        var emitter = new RecordingSseEmitter();
        var streams = new DesktopEventStreamService(events, 4, 2, () -> emitter);

        streams.subscribe("session-1", "world-1", 0);
        streams.deliverEvents();

        // stream.connected + matching WORLD + SYSTEM + matching SESSION
        assertThat(emitter.sendCount()).isEqualTo(4);
        assertThat(emitter.events())
                .extracting(GahyeonEvent::sequence)
                .containsExactly(2L, 3L, 4L);
    }

    @Test
    void splitsOversizedDeltasWithoutBreakingUnicodeOrChangingContent() {
        String delta = "가".repeat(16_383) + "😀" + "나".repeat(16_384);
        var fragments = DesktopEventStreamService.splitDelta(delta);

        assertThat(fragments).hasSize(3).allSatisfy(fragment ->
                assertThat(fragment.length()).isLessThanOrEqualTo(16_384));
        assertThat(fragments.stream().collect(Collectors.joining())).isEqualTo(delta);
        assertThat(fragments).allSatisfy(fragment -> {
            assertThat(Character.isHighSurrogate(fragment.charAt(fragment.length() - 1))).isFalse();
            assertThat(Character.isLowSurrogate(fragment.charAt(0))).isFalse();
        });
    }

    private static GahyeonEvent event(
            long sequence,
            EventScope scope,
            ConversationSessionId sessionId) {
        return new GahyeonEvent(
                GahyeonEvent.CURRENT_SCHEMA_VERSION,
                "event-" + sequence,
                sequence,
                "test.event",
                scope,
                sessionId,
                "correlation-" + sequence,
                Instant.EPOCH,
                Map.of());
    }

    private static final class RecordingSseEmitter extends SseEmitter {
        private final AtomicInteger sends = new AtomicInteger();
        private final List<GahyeonEvent> events = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sends.incrementAndGet();
            builder.build().forEach(item -> {
                if (item.getData() instanceof GahyeonEvent event) events.add(event);
            });
        }

        int sendCount() {
            return sends.get();
        }

        List<GahyeonEvent> events() {
            return List.copyOf(events);
        }
    }
}
