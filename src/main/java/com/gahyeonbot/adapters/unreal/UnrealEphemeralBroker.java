package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.adapters.unreal.protocol.UnrealDelivery;
import com.gahyeonbot.adapters.unreal.protocol.UnrealEnvelope;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Process-local outbound dispatcher with an isolated bounded serial queue per renderer. */
public final class UnrealEphemeralBroker {
    private static final int DEFAULT_PER_RENDERER_CAPACITY = 64;

    private final Clock clock;
    private final Executor deliveryExecutor;
    private final int perRendererCapacity;
    private final UnrealRuntimeMetrics metrics;
    private final ConcurrentHashMap<String, Subscriber> subscribers = new ConcurrentHashMap<>();

    /**
     * Atomically validates a caller-owned publication token and performs only the
     * bounded queue mutation supplied by this broker.
     */
    @FunctionalInterface
    public interface PublicationAdmission {
        boolean tryAdmit(Runnable boundedQueueAdmission);
    }

    private static final PublicationAdmission ALWAYS_ADMIT = boundedQueueAdmission -> {
        boundedQueueAdmission.run();
        return true;
    };

    /** Deterministic direct delivery for focused unit tests and small embedded use. */
    public UnrealEphemeralBroker(Clock clock) {
        this(clock, Runnable::run, DEFAULT_PER_RENDERER_CAPACITY, null);
    }

    public UnrealEphemeralBroker(
            Clock clock,
            Executor deliveryExecutor,
            int perRendererCapacity) {
        this(clock, deliveryExecutor, perRendererCapacity, null);
    }

    public UnrealEphemeralBroker(
            Clock clock,
            Executor deliveryExecutor,
            int perRendererCapacity,
            UnrealRuntimeMetrics metrics) {
        if (clock == null) throw new IllegalArgumentException("clock is required");
        if (deliveryExecutor == null) throw new IllegalArgumentException("deliveryExecutor is required");
        if (perRendererCapacity < 1) throw new IllegalArgumentException("perRendererCapacity must be positive");
        this.clock = clock;
        this.deliveryExecutor = deliveryExecutor;
        this.perRendererCapacity = perRendererCapacity;
        this.metrics = metrics;
    }

    public synchronized void subscribe(
            String connectionId,
            String sessionId,
            Consumer<UnrealEnvelope> consumer) {
        subscribe(connectionId, sessionId, consumer, () -> {});
    }

    public synchronized void subscribe(
            String connectionId,
            String sessionId,
            Consumer<UnrealEnvelope> consumer,
            Runnable onDeliveryFailure) {
        if (connectionId == null || connectionId.isBlank()) throw new IllegalArgumentException("connectionId is required");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (consumer == null) throw new IllegalArgumentException("consumer is required");
        if (onDeliveryFailure == null) throw new IllegalArgumentException("onDeliveryFailure is required");
        Subscriber previous = subscribers.put(
                connectionId, new Subscriber(sessionId, consumer, onDeliveryFailure));
        if (previous != null) previous.detach();
    }

    public void unsubscribe(String connectionId) {
        Subscriber removed = subscribers.remove(connectionId);
        if (removed != null) removed.detach();
    }

    /** Removes one connection and reports whether its session now has no subscribers. */
    public synchronized String unsubscribeLastSession(String connectionId) {
        Subscriber removed = subscribers.remove(connectionId);
        if (removed == null) return null;
        removed.detach();
        boolean remaining = subscribers.values().stream()
                .anyMatch(candidate -> candidate.sessionId.equals(removed.sessionId));
        return remaining ? null : removed.sessionId;
    }

    public synchronized boolean hasSubscribers(String sessionId) {
        return sessionId != null && subscribers.values().stream()
                .anyMatch(candidate -> candidate.sessionId.equals(sessionId));
    }

    /** Returns the number of renderer queues that admitted this event without blocking the caller. */
    public int publish(
            String sessionId,
            String type,
            String correlationId,
            Map<String, Object> payload) {
        return publishIf(sessionId, type, correlationId, payload, ALWAYS_ADMIT);
    }

    /**
     * Publishes only while the caller-owned generation remains current. The caller
     * atomically validates its token around the broker's bounded queue mutation;
     * executor scheduling, delivery, and failure cleanup remain outside that claim.
     * Returns {@code -1} when the claim rejects every matching queue before admission.
     */
    public int publishIf(
            String sessionId,
            String type,
            String correlationId,
            Map<String, Object> payload,
            PublicationAdmission publicationAdmission) {
        if (publicationAdmission == null) {
            throw new IllegalArgumentException("publicationAdmission is required");
        }
        var envelope = new UnrealEnvelope(
                UnrealEnvelope.PROTOCOL,
                UnrealEnvelope.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                type,
                clock.instant(),
                sessionId,
                correlationId,
                UnrealDelivery.EPHEMERAL.wireValue(),
                null,
                payload);
        int admitted = 0;
        boolean claimRejected = false;
        for (var entry : subscribers.entrySet()) {
            Subscriber subscriber = entry.getValue();
            if (!subscriber.sessionId.equals(sessionId)) continue;
            EnqueueResult result = subscriber.enqueue(
                    envelope, perRendererCapacity, publicationAdmission);
            if (result == EnqueueResult.REJECTED) continue;
            if (result == EnqueueResult.STALE) {
                claimRejected = true;
                continue;
            }
            if (result == EnqueueResult.FULL) {
                fail(entry.getKey(), subscriber, "queue_full");
                continue;
            }
            if (result == EnqueueResult.ACCEPTED) {
                admitted++;
                admitted();
            } else if (result == EnqueueResult.START_DRAIN) {
                try {
                    deliveryExecutor.execute(() -> drain(entry.getKey(), subscriber));
                    if (subscribers.get(entry.getKey()) == subscriber) {
                        admitted++;
                        admitted();
                    }
                } catch (RuntimeException rejected) {
                    fail(entry.getKey(), subscriber, "executor_rejected");
                }
            }
        }
        return admitted == 0 && claimRejected ? -1 : admitted;
    }

    /** Enqueues an already-versioned envelope for exactly one renderer without blocking the caller. */
    public boolean publishTo(String connectionId, UnrealEnvelope envelope) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId is required");
        }
        if (envelope == null) throw new IllegalArgumentException("envelope is required");
        Subscriber subscriber = subscribers.get(connectionId);
        if (subscriber == null) return false;
        EnqueueResult result = subscriber.enqueue(envelope, perRendererCapacity, ALWAYS_ADMIT);
        if (result == EnqueueResult.REJECTED) return false;
        if (result == EnqueueResult.FULL) {
            fail(connectionId, subscriber, "queue_full");
            return false;
        }
        if (result == EnqueueResult.ACCEPTED) {
            admitted();
            return true;
        }
        try {
            deliveryExecutor.execute(() -> drain(connectionId, subscriber));
            if (subscribers.get(connectionId) == subscriber) {
                admitted();
                return true;
            }
        } catch (RuntimeException rejected) {
            fail(connectionId, subscriber, "executor_rejected");
        }
        return false;
    }

    private void drain(String connectionId, Subscriber subscriber) {
        while (true) {
            UnrealEnvelope next = subscriber.next();
            if (next == null) return;
            try {
                subscriber.consumer.accept(next);
            } catch (RuntimeException failed) {
                fail(connectionId, subscriber, "delivery_failed");
                return;
            }
        }
    }

    private void fail(String connectionId, Subscriber subscriber, String reason) {
        if (!subscribers.remove(connectionId, subscriber)) return;
        subscriber.detach();
        if (metrics != null) metrics.outboundRendererDetached(reason);
        try {
            subscriber.onDeliveryFailure.run();
        } catch (RuntimeException ignored) {
            // The failed subscriber is already detached. Cleanup callbacks
            // must not prevent delivery admission for healthy renderers.
        }
    }

    private void admitted() {
        if (metrics != null) metrics.outboundAdmitted();
    }

    int subscriberCount() {
        return subscribers.size();
    }

    synchronized int sessionCount() {
        return (int) subscribers.values().stream()
                .map(candidate -> candidate.sessionId)
                .distinct()
                .count();
    }

    int queuedMessageCount() {
        return subscribers.values().stream().mapToInt(Subscriber::queueSize).sum();
    }

    private enum EnqueueResult { ACCEPTED, START_DRAIN, FULL, REJECTED, STALE }

    private static final class Subscriber {
        private final String sessionId;
        private final Consumer<UnrealEnvelope> consumer;
        private final Runnable onDeliveryFailure;
        private final ArrayDeque<UnrealEnvelope> queue = new ArrayDeque<>();
        private boolean draining;
        private boolean detached;

        private Subscriber(
                String sessionId,
                Consumer<UnrealEnvelope> consumer,
                Runnable onDeliveryFailure) {
            this.sessionId = sessionId;
            this.consumer = consumer;
            this.onDeliveryFailure = onDeliveryFailure;
        }

        synchronized EnqueueResult enqueue(
                UnrealEnvelope envelope,
                int capacity,
                PublicationAdmission publicationAdmission) {
            if (detached) return EnqueueResult.REJECTED;
            EnqueueResult[] result = new EnqueueResult[1];
            boolean claimed = publicationAdmission.tryAdmit(() -> {
                if (queue.size() >= capacity) {
                    result[0] = EnqueueResult.FULL;
                    return;
                }
                queue.addLast(envelope);
                if (draining) {
                    result[0] = EnqueueResult.ACCEPTED;
                    return;
                }
                draining = true;
                result[0] = EnqueueResult.START_DRAIN;
            });
            return claimed ? result[0] : EnqueueResult.STALE;
        }

        synchronized UnrealEnvelope next() {
            if (detached) return null;
            UnrealEnvelope next = queue.pollFirst();
            if (next == null) draining = false;
            return next;
        }

        synchronized void detach() {
            detached = true;
            draining = false;
            queue.clear();
        }

        synchronized int queueSize() {
            return queue.size();
        }
    }
}
