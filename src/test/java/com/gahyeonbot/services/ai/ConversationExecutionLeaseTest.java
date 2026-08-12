package com.gahyeonbot.services.ai;

import com.gahyeonbot.services.ai.agent.AgentStreamCancelledException;
import com.gahyeonbot.services.ai.agent.AgentStreamObserver;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationExecutionLeaseTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void delayedNotificationDoesNotInterruptAnUnboundReusedRunner() throws Exception {
        AtomicInteger cancellationAccounting = new AtomicInteger();
        AtomicInteger runCancellation = new AtomicInteger();
        ConversationExecutionLease lease = new ConversationExecutionLease(
                observer(delta -> {}, () -> false), cancellationAccounting::incrementAndGet);
        CountDownLatch reused = new CountDownLatch(1);
        CountDownLatch releaseReuse = new CountDownLatch(1);
        AtomicBoolean reuseWasInterrupted = new AtomicBoolean();
        Thread runner = daemonThread(() -> {
            reused.countDown();
            try {
                releaseReuse.await();
            } catch (InterruptedException interrupted) {
                reuseWasInterrupted.set(true);
            }
        });

        lease.bindRunner(runner);
        lease.onRunStarted("run-1", runCancellation::incrementAndGet);
        ConversationExecutionLease.Cancellation cancellation = lease.markCancelled();
        lease.unbindRunner(runner);
        runner.start();
        assertTrue(reused.await(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));

        cancellation.notifyCancellation();
        cancellation.notifyCancellation();
        releaseReuse.countDown();
        runner.join(TIMEOUT.toMillis());

        assertFalse(runner.isAlive());
        assertFalse(reuseWasInterrupted.get());
        assertEquals(1, cancellationAccounting.get());
        assertEquals(1, runCancellation.get());
    }

    @Test
    void leaseInterruptIsClearedBeforeTheRunnerThreadIsReused() throws Exception {
        ConversationExecutionLease lease = new ConversationExecutionLease(
                observer(delta -> {}, () -> false), () -> {});
        CountDownLatch bound = new CountDownLatch(1);
        CountDownLatch reused = new CountDownLatch(1);
        AtomicBoolean interruptSurvivedUnbind = new AtomicBoolean();
        Thread runner = daemonThread(() -> {
            Thread current = Thread.currentThread();
            lease.bindRunner(current);
            bound.countDown();
            while (!current.isInterrupted()) Thread.onSpinWait();
            lease.unbindRunner(current);
            interruptSurvivedUnbind.set(current.isInterrupted());
            reused.countDown();
        });
        runner.start();
        assertTrue(bound.await(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));

        lease.cancel();

        assertTrue(reused.await(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
        runner.join(TIMEOUT.toMillis());
        assertFalse(runner.isAlive());
        assertFalse(interruptSurvivedUnbind.get());
    }

    @Test
    void unbindDoesNotClearAnExternalInterrupt() throws Exception {
        ConversationExecutionLease lease = new ConversationExecutionLease(
                observer(delta -> {}, () -> false), () -> {});
        CountDownLatch bound = new CountDownLatch(1);
        AtomicBoolean interruptSurvivedUnbind = new AtomicBoolean();
        Thread runner = daemonThread(() -> {
            Thread current = Thread.currentThread();
            lease.bindRunner(current);
            bound.countDown();
            while (!current.isInterrupted()) Thread.onSpinWait();
            lease.unbindRunner(current);
            interruptSurvivedUnbind.set(current.isInterrupted());
            Thread.interrupted();
        });
        runner.start();
        assertTrue(bound.await(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));

        runner.interrupt();

        runner.join(TIMEOUT.toMillis());
        assertFalse(runner.isAlive());
        assertTrue(interruptSurvivedUnbind.get());
    }

    @Test
    void blockingCancellationCheckDoesNotDelayMarkCancelled() throws Exception {
        CountDownLatch checkEntered = new CountDownLatch(1);
        CountDownLatch releaseCheck = new CountDownLatch(1);
        ConversationExecutionLease lease = new ConversationExecutionLease(
                observer(delta -> {}, () -> {
                    checkEntered.countDown();
                    await(releaseCheck);
                    return false;
                }), () -> {});
        AtomicReference<Throwable> deliveryFailure = new AtomicReference<>();
        Thread delivery = daemonThread(() -> captureFailure(
                () -> lease.streamingObserver().onTextDelta("hello"), deliveryFailure));
        delivery.start();
        assertTrue(checkEntered.await(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));

        try {
            assertMarkReturnsWhileObserverIsBlocked(lease);
        } finally {
            releaseCheck.countDown();
        }
        delivery.join(TIMEOUT.toMillis());

        assertFalse(delivery.isAlive());
        assertTrue(deliveryFailure.get() instanceof AgentStreamCancelledException);
    }

    @Test
    void blockingTextDeltaDoesNotDelayMarkCancelled() throws Exception {
        CountDownLatch deltaEntered = new CountDownLatch(1);
        CountDownLatch releaseDelta = new CountDownLatch(1);
        ConversationExecutionLease lease = new ConversationExecutionLease(
                observer(delta -> {
                    deltaEntered.countDown();
                    await(releaseDelta);
                }, () -> false), () -> {});
        AtomicReference<Throwable> deliveryFailure = new AtomicReference<>();
        Thread delivery = daemonThread(() -> captureFailure(
                () -> lease.streamingObserver().onTextDelta("hello"), deliveryFailure));
        delivery.start();
        assertTrue(deltaEntered.await(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));

        try {
            assertMarkReturnsWhileObserverIsBlocked(lease);
        } finally {
            releaseDelta.countDown();
        }
        delivery.join(TIMEOUT.toMillis());

        assertFalse(delivery.isAlive());
        assertNull(deliveryFailure.get());
    }

    @Test
    void reentrantTextDeltaCanCancelTheLeaseExactlyOnce() {
        AtomicInteger cancellationAccounting = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        AtomicReference<ConversationExecutionLease> leaseReference = new AtomicReference<>();
        ConversationExecutionLease lease = new ConversationExecutionLease(
                observer(delta -> {
                    delivered.incrementAndGet();
                    leaseReference.get().cancel();
                }, () -> false), cancellationAccounting::incrementAndGet);
        leaseReference.set(lease);

        assertTimeoutPreemptively(TIMEOUT,
                () -> lease.streamingObserver().onTextDelta("hello"));
        lease.cancel();

        assertEquals(1, delivered.get());
        assertEquals(1, cancellationAccounting.get());
        assertTrue(lease.isCancelled());
    }

    @Test
    void textDeltaAfterCancellationNeverReachesTheDelegate() {
        AtomicInteger delivered = new AtomicInteger();
        AtomicInteger cancellationChecks = new AtomicInteger();
        ConversationExecutionLease lease = new ConversationExecutionLease(
                observer(delta -> delivered.incrementAndGet(), () -> {
                    cancellationChecks.incrementAndGet();
                    return false;
                }), () -> {});
        lease.cancel();

        assertThrows(AgentStreamCancelledException.class,
                () -> lease.streamingObserver().onTextDelta("late"));

        assertEquals(0, delivered.get());
        assertEquals(0, cancellationChecks.get());
    }

    @Test
    void delegateCancellationRejectsSideEffectClaimAndAccountsOnce() {
        AtomicInteger cancellationAccounting = new AtomicInteger();
        ConversationExecutionLease lease = new ConversationExecutionLease(
                observer(delta -> {}, () -> true), cancellationAccounting::incrementAndGet);

        assertFalse(lease.tryStartSideEffect());
        assertFalse(lease.tryStartSideEffect());

        assertTrue(lease.isCancelled());
        assertEquals(1, cancellationAccounting.get());
    }

    private static void assertMarkReturnsWhileObserverIsBlocked(
            ConversationExecutionLease lease) throws Exception {
        CountDownLatch returned = new CountDownLatch(1);
        Thread cancellation = daemonThread(() -> {
            lease.markCancelled();
            returned.countDown();
        });
        cancellation.start();
        assertTrue(returned.await(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS),
                "markCancelled must not wait for an observer callback");
        cancellation.join(TIMEOUT.toMillis());
        assertFalse(cancellation.isAlive());
    }

    private static AgentStreamObserver observer(
            java.util.function.Consumer<String> onDelta,
            java.util.function.BooleanSupplier cancelled) {
        return new AgentStreamObserver() {
            @Override
            public void onTextDelta(String delta) {
                onDelta.accept(delta);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.getAsBoolean();
            }
        };
    }

    private static Thread daemonThread(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        return thread;
    }

    private static void captureFailure(Runnable task, AtomicReference<Throwable> failure) {
        try {
            task.run();
        } catch (Throwable thrown) {
            failure.set(thrown);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
