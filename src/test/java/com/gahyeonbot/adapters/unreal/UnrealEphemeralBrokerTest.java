package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.adapters.unreal.protocol.UnrealDelivery;
import com.gahyeonbot.adapters.unreal.protocol.UnrealEnvelope;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class UnrealEphemeralBrokerTest {
    @Test
    void generationAdvanceCannotSplitClaimFromBoundedQueueAdmission() throws Exception {
        var drains = new ArrayDeque<Runnable>();
        var delivered = new ArrayList<String>();
        var broker = new UnrealEphemeralBroker(
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), drains::add, 8);
        broker.subscribe("desktop", "session-1", event -> delivered.add(event.type()));
        var generationMonitor = new Object();
        var generation = new AtomicInteger(1);
        var claimEntered = new CountDownLatch(1);
        var advanceAttempted = new CountDownLatch(1);
        var admissionCompleted = new AtomicBoolean();
        var advancedBeforeAdmissionCompleted = new AtomicBoolean();
        UnrealEphemeralBroker.PublicationAdmission generationOne = boundedAdmission -> {
            synchronized (generationMonitor) {
                claimEntered.countDown();
                try {
                    if (!advanceAttempted.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("generation advance did not race admission");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                if (generation.get() != 1) return false;
                boundedAdmission.run();
                admissionCompleted.set(true);
                return true;
            }
        };
        var advancing = Executors.newSingleThreadExecutor();
        try {
            var advance = advancing.submit(() -> {
                assertThat(claimEntered.await(2, TimeUnit.SECONDS)).isTrue();
                advanceAttempted.countDown();
                synchronized (generationMonitor) {
                    advancedBeforeAdmissionCompleted.set(!admissionCompleted.get());
                    generation.set(2);
                }
                return null;
            });

            assertThat(broker.publishIf(
                    "session-1", "speech.prepared", "generation-1", Map.of(), generationOne))
                    .isEqualTo(1);
            advance.get(2, TimeUnit.SECONDS);

            assertThat(advancedBeforeAdmissionCompleted).isFalse();
            assertThat(delivered).isEmpty();
            assertThat(drains).hasSize(1);
            drains.remove().run();
            assertThat(delivered).containsExactly("speech.prepared");

            UnrealEphemeralBroker.PublicationAdmission staleGeneration = boundedAdmission -> {
                synchronized (generationMonitor) {
                    if (generation.get() != 1) return false;
                    boundedAdmission.run();
                    return true;
                }
            };
            assertThat(broker.publishIf(
                    "session-1", "speech.prepared", "stale-generation", Map.of(), staleGeneration))
                    .isEqualTo(-1);
            assertThat(drains).isEmpty();
        } finally {
            advancing.shutdownNow();
        }
    }

    @Test
    void targetedDurableDeliveryUsesOnlyTheSelectedRendererQueue() {
        var broker = new UnrealEphemeralBroker(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var first = new ArrayList<String>();
        var second = new ArrayList<String>();
        broker.subscribe("desktop", "session-1", event -> first.add(event.messageId()));
        broker.subscribe("looking-glass", "session-1", event -> second.add(event.messageId()));

        assertThat(broker.publishTo("desktop", durable("event-1", 1))).isTrue();

        assertThat(first).containsExactly("event-1");
        assertThat(second).isEmpty();
        assertThat(broker.publishTo("missing", durable("event-2", 2))).isFalse();
    }

    @Test
    void deliversOnlyToMatchingSessionAndDoesNotReplay() {
        var broker = new UnrealEphemeralBroker(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var first = new ArrayList<String>();
        var other = new ArrayList<String>();
        broker.subscribe("connection-1", "session-1", envelope -> first.add(envelope.type()));
        broker.subscribe("connection-2", "session-2", envelope -> other.add(envelope.type()));

        assertThat(broker.publish("session-1", "speech.prepared", "correlation-1", Map.of()))
                .isEqualTo(1);
        assertThat(first).containsExactly("speech.prepared");
        assertThat(other).isEmpty();

        broker.unsubscribe("connection-1");
        assertThat(broker.publish("session-1", "speech.prepared", "correlation-2", Map.of()))
                .isZero();
    }

    @Test
    void reportsLastSubscriberOnlyAfterFinalConnectionLeavesSession() {
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        broker.subscribe("connection-1", "session-1", ignored -> {});
        broker.subscribe("connection-2", "session-1", ignored -> {});

        assertThat(broker.unsubscribeLastSession("connection-1")).isNull();
        assertThat(broker.unsubscribeLastSession("connection-2")).isEqualTo("session-1");
        assertThat(broker.unsubscribeLastSession("connection-2")).isNull();
    }

    @Test
    void deliveryFailureDetachesOnceAndDoesNotBlockHealthyRenderer() {
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        var cleanupCalls = new AtomicInteger();
        var healthy = new ArrayList<String>();
        broker.subscribe("failed", "session-1", ignored -> {
            throw new IllegalStateException("socket failed");
        }, cleanupCalls::incrementAndGet);
        broker.subscribe("healthy", "session-1", envelope -> healthy.add(envelope.type()));

        assertThat(broker.publish("session-1", "speech.prepared", "one", Map.of()))
                .isEqualTo(1);
        assertThat(cleanupCalls.get()).isEqualTo(1);
        assertThat(healthy).containsExactly("speech.prepared");
        assertThat(broker.subscriberCount()).isEqualTo(1);

        assertThat(broker.publish("session-1", "speech.sequence.ended", "two", Map.of()))
                .isEqualTo(1);
        assertThat(cleanupCalls.get()).isEqualTo(1);
    }

    @Test
    void concurrentPublishRunsFailedLeaseCleanupExactlyOnce() throws Exception {
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        var cleanupCalls = new AtomicInteger();
        broker.subscribe("failed", "session-1", ignored -> {
            throw new IllegalStateException("socket failed");
        }, cleanupCalls::incrementAndGet);
        int publishers = 16;
        var ready = new CountDownLatch(publishers);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(publishers);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int index = 0; index < publishers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    broker.publish("session-1", "speech.prepared", "concurrent", Map.of());
                    return null;
                }));
            }
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var future : futures) future.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(cleanupCalls.get()).isEqualTo(1);
        assertThat(broker.subscriberCount()).isZero();
    }

    @Test
    void slowRendererDoesNotBlockPublisherOrHealthyRenderer() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var broker = new UnrealEphemeralBroker(Clock.systemUTC(), executor, 8);
            var slowStarted = new CountDownLatch(1);
            var releaseSlow = new CountDownLatch(1);
            var fastDelivered = new CountDownLatch(1);
            broker.subscribe("slow-go", "session-1", ignored -> {
                slowStarted.countDown();
                try {
                    releaseSlow.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            });
            broker.subscribe("desktop", "session-1", ignored -> fastDelivered.countDown());

            assertThat(broker.publish(
                    "session-1", "speech.prepared", "nonblocking", Map.of()))
                    .isEqualTo(2);
            assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(fastDelivered.await(1, TimeUnit.SECONDS)).isTrue();
            releaseSlow.countDown();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void slowRendererDoesNotBlockTargetedDurableReplayForHealthyRenderer() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var broker = new UnrealEphemeralBroker(Clock.systemUTC(), executor, 8);
            var slowStarted = new CountDownLatch(1);
            var releaseSlow = new CountDownLatch(1);
            var fastDelivered = new CountDownLatch(1);
            broker.subscribe("slow-go", "session-1", ignored -> {
                slowStarted.countDown();
                try {
                    releaseSlow.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            broker.subscribe("desktop", "session-1", ignored -> fastDelivered.countDown());

            assertThat(broker.publishTo("slow-go", durable("slow-event", 1))).isTrue();
            assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(broker.publishTo("desktop", durable("desktop-event", 1))).isTrue();
            assertThat(fastDelivered.await(1, TimeUnit.SECONDS)).isTrue();
            releaseSlow.countDown();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void eachRendererQueuePreservesPublishOrder() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            var broker = new UnrealEphemeralBroker(Clock.systemUTC(), executor, 128);
            int count = 100;
            var delivered = java.util.Collections.synchronizedList(new ArrayList<Integer>());
            var complete = new CountDownLatch(count);
            broker.subscribe("desktop", "session-1", envelope -> {
                delivered.add((Integer) envelope.payload().get("index"));
                complete.countDown();
            });
            for (int index = 0; index < count; index++) {
                assertThat(broker.publish(
                        "session-1", "attention.target", "ordered", Map.of("index", index)))
                        .isEqualTo(1);
            }
            assertThat(complete.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(delivered).containsExactlyElementsOf(
                    java.util.stream.IntStream.range(0, count).boxed().toList());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void perRendererBackpressureDetachesOnlySaturatedRenderer() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var broker = new UnrealEphemeralBroker(Clock.systemUTC(), executor, 1);
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var cleanup = new AtomicInteger();
            broker.subscribe("slow", "session-1", ignored -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, cleanup::incrementAndGet);
            assertThat(broker.publish("session-1", "speech.prepared", "one", Map.of()))
                    .isEqualTo(1);
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(broker.publish("session-1", "speech.prepared", "two", Map.of()))
                    .isEqualTo(1);

            assertThat(broker.publish("session-1", "speech.prepared", "three", Map.of()))
                    .isZero();
            assertThat(cleanup.get()).isEqualTo(1);
            assertThat(broker.subscriberCount()).isZero();
            release.countDown();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recordsAdmissionAndBoundedDetachReason() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new UnrealRuntimeMetrics(registry);
            var broker = new UnrealEphemeralBroker(Clock.systemUTC(), executor, 1, metrics);
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            broker.subscribe("slow", "session-1", ignored -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });

            broker.publish("session-1", "speech.prepared", "one", Map.of());
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            broker.publish("session-1", "speech.prepared", "two", Map.of());
            broker.publish("session-1", "speech.prepared", "three", Map.of());

            assertThat(registry.counter("gahyeon.unreal.outbound.admitted").count()).isEqualTo(2);
            assertThat(registry.counter(
                    "gahyeon.unreal.outbound.renderer.detached", "reason", "queue_full").count())
                    .isEqualTo(1);
            release.countDown();
        } finally {
            executor.shutdownNow();
        }
    }

    private UnrealEnvelope durable(String messageId, long sequence) {
        return new UnrealEnvelope(
                UnrealEnvelope.PROTOCOL,
                UnrealEnvelope.SCHEMA_VERSION,
                messageId,
                "cognition.response.completed",
                Instant.EPOCH,
                "session-1",
                "correlation-1",
                UnrealDelivery.DURABLE.wireValue(),
                sequence,
                Map.of());
    }
}
