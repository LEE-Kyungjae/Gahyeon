package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.event.GahyeonEventQuery;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
        streams.subscribe("session-1", 0);
        streams.subscribe("session-1", 0);
        assertThat(streams.activeSubscriptions()).isEqualTo(2);

        assertThatThrownBy(() -> streams.subscribe("session-1", 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429 TOO_MANY_REQUESTS");
        streams.subscribe("session-2", 0);
        streams.subscribe("session-3", 0);
        assertThatThrownBy(() -> streams.subscribe("session-4", 0))
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
                        streams.subscribe("same-session", 0);
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
        assertThatThrownBy(() -> streams.subscribe("s".repeat(181), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(streams.activeSubscriptions()).isZero();
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
}
