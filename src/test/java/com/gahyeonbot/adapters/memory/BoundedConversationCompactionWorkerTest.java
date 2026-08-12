package com.gahyeonbot.adapters.memory;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.memory.MemorySummarizationPort;
import com.gahyeonbot.entity.ConversationHistory;
import com.gahyeonbot.repository.ConversationHistoryRepository;
import com.gahyeonbot.services.ai.ConversationHistoryService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BoundedConversationCompactionWorkerTest {

    @Test
    void rememberReturnsWithoutWaitingForBlockedSummarization() throws Exception {
        var repository = mock(ConversationHistoryRepository.class);
        var summarizerEntered = new CountDownLatch(1);
        var releaseSummarizer = new CountDownLatch(1);
        var summarizerCompleted = new CountDownLatch(1);
        MemorySummarizationPort summarizer = (user, assistant) -> {
            summarizerEntered.countDown();
            await(releaseSummarizer);
            summarizerCompleted.countDown();
            return "요약";
        };
        when(repository.findUnsummarizedOldConversations(7L, 5, 4))
                .thenReturn(List.of(conversation(1L, 7L, "오래된 질문", "오래된 답")));

        var executor = executor(1);
        var caller = Executors.newSingleThreadExecutor();
        try {
            var worker = worker(repository, summarizer, executor, new SimpleMeterRegistry());
            var service = new ConversationHistoryService(repository, worker);

            var remember = caller.submit(() -> service.remember(new ActorId(7L), "새 질문", "새 답"));

            // A blocked provider owns only the maintenance worker, never the response caller.
            remember.get(1, TimeUnit.SECONDS);
            assertThat(summarizerEntered.await(1, TimeUnit.SECONDS)).isTrue();
            verify(repository, atLeastOnce()).save(any(ConversationHistory.class));
        } finally {
            releaseSummarizer.countDown();
            assertThat(summarizerCompleted.await(1, TimeUnit.SECONDS)).isTrue();
            caller.shutdownNow();
            executor.shutdownNow();
        }
    }

    @Test
    void submitsOnlyAfterTheConversationTransactionCommits() throws Exception {
        var repository = mock(ConversationHistoryRepository.class);
        var queried = new CountDownLatch(1);
        when(repository.findUnsummarizedOldConversations(7L, 5, 4)).thenAnswer(ignored -> {
            queried.countDown();
            return List.of();
        });
        var executor = executor(1);
        var metrics = new SimpleMeterRegistry();
        var worker = worker(repository, (user, assistant) -> "요약", executor, metrics);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            worker.requestCompaction(new ActorId(7L));

            assertThat(executor.getTaskCount()).isZero();
            assertThat(queried.getCount()).isOne();
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            assertThat(queried.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(metrics.counter(
                    "gahyeonbot.memory.compaction.requests", "outcome", "deferred").count())
                    .isEqualTo(1.0);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            executor.shutdownNow();
        }
    }

    @Test
    void boundedQueueRejectsOverflowAndAcceptsItAgainAfterCapacityRecovers() throws Exception {
        var repository = mock(ConversationHistoryRepository.class);
        var firstProviderCall = new CountDownLatch(1);
        var releaseFirstProviderCall = new CountDownLatch(1);
        var secondActorProcessed = new CountDownLatch(1);
        var thirdActorProcessed = new CountDownLatch(1);
        when(repository.findUnsummarizedOldConversations(any(Long.class), eq(5), eq(4)))
                .thenAnswer(invocation -> {
                    long actorId = invocation.getArgument(0);
                    if (actorId == 1L) {
                        return List.of(conversation(11L, 1L, "block", "답"));
                    }
                    if (actorId == 2L) secondActorProcessed.countDown();
                    if (actorId == 3L) thirdActorProcessed.countDown();
                    return List.of();
                });
        MemorySummarizationPort summarizer = (user, assistant) -> {
            firstProviderCall.countDown();
            await(releaseFirstProviderCall);
            return "요약";
        };
        var executor = executor(1);
        var metrics = new SimpleMeterRegistry();
        var worker = worker(repository, summarizer, executor, metrics);

        try {
            worker.requestCompaction(new ActorId(1L));
            assertThat(firstProviderCall.await(1, TimeUnit.SECONDS)).isTrue();

            worker.requestCompaction(new ActorId(2L));
            assertThat(executor.getQueue()).hasSize(1);
            worker.requestCompaction(new ActorId(3L));

            assertThat(executor.getQueue()).hasSize(1);
            assertThat(metrics.counter(
                    "gahyeonbot.memory.compaction.requests", "outcome", "rejected").count())
                    .isEqualTo(1.0);

            releaseFirstProviderCall.countDown();
            assertThat(secondActorProcessed.await(1, TimeUnit.SECONDS)).isTrue();

            // Rejection removes the actor marker. Once capacity returns, the same durable work
            // can be requested again and is processed normally.
            worker.requestCompaction(new ActorId(3L));
            assertThat(thirdActorProcessed.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFirstProviderCall.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void providerFailureLeavesConversationRetryable() throws Exception {
        var repository = mock(ConversationHistoryRepository.class);
        var candidate = conversation(1L, 7L, "질문", "답");
        when(repository.findUnsummarizedOldConversations(7L, 5, 4))
                .thenReturn(List.of(candidate));
        var attempts = new AtomicInteger();
        var recovered = new CountDownLatch(1);
        MemorySummarizationPort summarizer = (user, assistant) -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("provider down");
            recovered.countDown();
            return "복구된 요약";
        };
        var executor = executor(1);
        var worker = worker(repository, summarizer, executor, new SimpleMeterRegistry());

        try {
            worker.requestCompaction(new ActorId(7L));
            awaitAttempts(attempts, 1);
            assertThat(candidate.getSummarized()).isFalse();

            worker.requestCompaction(new ActorId(7L));
            assertThat(recovered.await(1, TimeUnit.SECONDS)).isTrue();
            // The provider signals recovery immediately before returning; persistence happens on
            // the next worker instruction, so wait for that externally observable completion.
            verify(repository, timeout(1_000)).save(candidate);
            assertThat(candidate.getSummarized()).isTrue();
            assertThat(candidate.getSummary()).isEqualTo("복구된 요약");
        } finally {
            executor.shutdownNow();
        }
    }

    private BoundedConversationCompactionWorker worker(
            ConversationHistoryRepository repository,
            MemorySummarizationPort summarizer,
            ThreadPoolExecutor executor,
            SimpleMeterRegistry metrics) {
        return new BoundedConversationCompactionWorker(repository, summarizer, executor, metrics, 4);
    }

    private ThreadPoolExecutor executor(int queueCapacity) {
        return new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    var thread = new Thread(runnable, "memory-compaction-test");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private ConversationHistory conversation(long id, long actorId, String user, String assistant) {
        return ConversationHistory.builder()
                .id(id)
                .actorId(actorId)
                .userMessage(user)
                .aiResponse(assistant)
                .summarized(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", interrupted);
        }
    }

    private static void awaitAttempts(AtomicInteger attempts, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (attempts.get() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(attempts.get()).isGreaterThanOrEqualTo(expected);
    }
}
