package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.event.GahyeonEventQuery;
import com.gahyeonbot.core.event.GahyeonEvent;
import com.gahyeonbot.core.event.EventScopeType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
public class DesktopEventStreamService {
    private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final int READ_BATCH_SIZE = 100;

    private final GahyeonEventQuery events;
    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    public DesktopEventStreamService(GahyeonEventQuery events) {
        this.events = events;
    }

    public SseEmitter subscribe(String sessionId, long afterSequence) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId가 필요합니다.");
        }
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence은 0 이상이어야 합니다.");
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

    @Scheduled(fixedDelayString = "${gahyeon.desktop.event-poll-millis:250}")
    void deliverEvents() {
        subscriptions.forEach(this::deliverEvents);
    }

    private void deliverEvents(String subscriptionId, Subscription subscription) {
        try {
            for (GahyeonEvent event : events.after(subscription.cursor, READ_BATCH_SIZE)) {
                subscription.cursor = event.sequence();
                if (!isVisibleTo(event, subscription.sessionId)) continue;
                subscription.emitter.send(SseEmitter.event()
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
        return event.scope().type() == EventScopeType.WORLD
                || event.scope().type() == EventScopeType.SYSTEM
                || event.scope().type() == EventScopeType.SESSION
                && event.scope().id().equals(sessionId);
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
    }
}
