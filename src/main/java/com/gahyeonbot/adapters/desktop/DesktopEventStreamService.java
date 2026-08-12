package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.event.GahyeonEventQuery;
import com.gahyeonbot.core.event.GahyeonEvent;
import com.gahyeonbot.core.event.EventScopeType;
import com.gahyeonbot.core.session.ClientSource;
import com.gahyeonbot.core.session.ConversationSessionId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
public class DesktopEventStreamService {
    private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final int READ_BATCH_SIZE = 100;
    private static final int MAXIMUM_DELTA_CHARACTERS = 16_384;

    private final GahyeonEventQuery events;
    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> conversationGenerations = new ConcurrentHashMap<>();
    private final int maximumSubscriptions;
    private final int maximumSubscriptionsPerSession;

    public DesktopEventStreamService(
            GahyeonEventQuery events,
            @Value("${gahyeon.desktop.maximum-event-subscriptions:128}") int maximumSubscriptions,
            @Value("${gahyeon.desktop.maximum-event-subscriptions-per-session:4}")
            int maximumSubscriptionsPerSession) {
        if (maximumSubscriptions < 1 || maximumSubscriptionsPerSession < 1
                || maximumSubscriptionsPerSession > maximumSubscriptions) {
            throw new IllegalArgumentException("Desktop event subscription limits are invalid");
        }
        this.events = events;
        this.maximumSubscriptions = maximumSubscriptions;
        this.maximumSubscriptionsPerSession = maximumSubscriptionsPerSession;
    }

    public synchronized SseEmitter subscribe(String sessionId, long afterSequence) {
        requireSessionId(sessionId);
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence은 0 이상이어야 합니다.");
        }
        long sessionSubscriptions = subscriptions.values().stream()
                .filter(subscription -> subscription.sessionId.equals(sessionId))
                .count();
        if (subscriptions.size() >= maximumSubscriptions
                || sessionSubscriptions >= maximumSubscriptionsPerSession) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Desktop event subscription capacity exceeded");
        }

        String subscriptionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        subscriptions.put(subscriptionId, new Subscription(sessionId, afterSequence, emitter));
        emitter.onCompletion(() -> subscriptions.remove(subscriptionId));
        emitter.onTimeout(() -> subscriptions.remove(subscriptionId));
        emitter.onError(error -> subscriptions.remove(subscriptionId));

        try {
            emitter.send(SseEmitter.event()
                    .name("stream.connected")
                    .data(new StreamCursor(afterSequence)));
        } catch (IOException error) {
            subscriptions.remove(subscriptionId);
            emitter.completeWithError(error);
        }
        return emitter;
    }

    int activeSubscriptions() {
        return subscriptions.size();
    }

    public String beginConversation(String sessionId) {
        requireSessionId(sessionId);
        String generation = UUID.randomUUID().toString();
        conversationGenerations.put(sessionId, generation);
        return generation;
    }

    public boolean isCurrentConversation(String sessionId, String generation) {
        requireSessionId(sessionId);
        return generation != null && generation.equals(conversationGenerations.get(sessionId));
    }

    public void finishConversation(String sessionId, String generation) {
        requireSessionId(sessionId);
        conversationGenerations.remove(sessionId, generation);
    }

    public void cancelConversation(String sessionId) {
        requireSessionId(sessionId);
        conversationGenerations.remove(sessionId);
    }

    public void publishConversationDelta(
            String sessionId,
            String requestId,
            String delta) {
        requireSessionId(sessionId);
        if (delta == null || delta.isEmpty()) return;
        for (String fragment : splitDelta(delta)) {
            subscriptions.forEach((subscriptionId, subscription) -> {
                if (!subscription.sessionId.equals(sessionId)) return;
                try {
                    subscription.send(SseEmitter.event()
                            .name("conversation.delta")
                            .data(Map.of("requestId", requestId, "delta", fragment)));
                } catch (Exception error) {
                    subscriptions.remove(subscriptionId);
                    subscription.emitter.completeWithError(error);
                }
            });
        }
    }

    static List<String> splitDelta(String delta) {
        if (delta == null || delta.isEmpty()) return List.of();
        List<String> fragments = new ArrayList<>();
        int start = 0;
        while (start < delta.length()) {
            int end = Math.min(delta.length(), start + MAXIMUM_DELTA_CHARACTERS);
            if (end < delta.length() && Character.isHighSurrogate(delta.charAt(end - 1))) {
                end--;
            }
            fragments.add(delta.substring(start, end));
            start = end;
        }
        return List.copyOf(fragments);
    }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()
                || sessionId.length() > ConversationSessionId.MAXIMUM_EXTERNAL_ID_CHARACTERS) {
            throw new IllegalArgumentException("sessionId가 필요하며 180자 이하여야 합니다.");
        }
    }

    @Scheduled(fixedDelayString = "${gahyeon.desktop.event-poll-millis:250}")
    void deliverEvents() {
        subscriptions.forEach(this::deliverEvents);
    }

    private void deliverEvents(String subscriptionId, Subscription subscription) {
        try {
            for (GahyeonEvent event : events.after(subscription.cursor, READ_BATCH_SIZE)) {
                subscription.cursor = event.sequence();
                if (!isVisibleTo(event, subscription.sessionId)) continue;
                subscription.send(SseEmitter.event()
                        .id(Long.toString(event.sequence()))
                        .name(event.type())
                        .data(event));
            }
        } catch (Exception error) {
            subscriptions.remove(subscriptionId);
            subscription.emitter.completeWithError(error);
        }
    }

    private boolean isVisibleTo(GahyeonEvent event, String sessionId) {
        String internalSessionId = ConversationSessionId
                .fromExternal(ClientSource.DESKTOP, sessionId).value();
        return event.scope().type() == EventScopeType.WORLD
                || event.scope().type() == EventScopeType.SYSTEM
                || event.scope().type() == EventScopeType.SESSION
                && (event.scope().id().equals(internalSessionId)
                    || event.scope().id().equals(sessionId));
    }

    record StreamCursor(long sequence) {}

    private static final class Subscription {
        private final String sessionId;
        private final SseEmitter emitter;
        private volatile long cursor;

        private Subscription(String sessionId, long cursor, SseEmitter emitter) {
            this.sessionId = sessionId;
            this.cursor = cursor;
            this.emitter = emitter;
        }

        private synchronized void send(SseEmitter.SseEventBuilder event) throws IOException {
            emitter.send(event);
        }
    }
}
