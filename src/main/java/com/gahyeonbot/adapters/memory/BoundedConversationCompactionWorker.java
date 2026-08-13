package com.gahyeonbot.adapters.memory;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.memory.MemoryCompactionPort;
import com.gahyeonbot.core.memory.MemorySummarizationPort;
import com.gahyeonbot.entity.ConversationHistory;
import com.gahyeonbot.repository.ConversationHistoryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs conversation compaction outside the response path on a dedicated bounded executor.
 *
 * <p>At most one queued job is retained per actor. The actor marker is removed when execution
 * begins, allowing one follow-up request to queue while a slow provider call is in progress.
 * A rejected job leaves its conversations unsummarized in the database and removes the marker,
 * so a later conversation can safely retry it.</p>
 */
@Slf4j
@Component
public final class BoundedConversationCompactionWorker implements MemoryCompactionPort {

    private static final int RECENT_CONVERSATION_COUNT = 5;
    private static final String METRIC_NAME = "gahyeonbot.memory.compaction.requests";

    private final ConversationHistoryRepository repository;
    private final MemorySummarizationPort summarizer;
    private final Executor executor;
    private final MeterRegistry meterRegistry;
    private final int batchSize;
    private final Set<ActorId> queuedActors = ConcurrentHashMap.newKeySet();

    public BoundedConversationCompactionWorker(
            ConversationHistoryRepository repository,
            MemorySummarizationPort summarizer,
            @Qualifier("memoryCompactionExecutor") Executor executor,
            MeterRegistry meterRegistry,
            @Value("${gahyeon.memory.compaction.batch-size:4}") int batchSize) {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("memory compaction batch size must be between 1 and 100");
        }
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer is required");
        this.executor = Objects.requireNonNull(executor, "executor is required");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry is required");
        this.batchSize = batchSize;
    }

    @Override
    public void requestCompaction(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId is required");
        Runnable request = () -> enqueue(actorId);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    request.run();
                }
            });
            increment("deferred");
            return;
        }
        request.run();
    }

    private void enqueue(ActorId actorId) {
        if (!queuedActors.add(actorId)) {
            increment("coalesced");
            return;
        }
        try {
            executor.execute(() -> {
                // The running job is no longer queued. A new save can now retain one follow-up.
                queuedActors.remove(actorId);
                compact(actorId);
            });
            increment("accepted");
        } catch (RejectedExecutionException rejected) {
            queuedActors.remove(actorId);
            increment("rejected");
            log.warn("메모리 압축 큐 포화 - actor: {}; 다음 대화에서 재시도합니다.", actorId.value());
        } catch (RuntimeException submissionFailure) {
            queuedActors.remove(actorId);
            increment("submission_failed");
            log.error("메모리 압축 예약 실패 - actor: {}", actorId.value(), submissionFailure);
        }
    }

    private void compact(ActorId actorId) {
        boolean completedBatch = true;
        try {
            var candidates = repository.findUnsummarizedOldConversations(
                    actorId.value(), RECENT_CONVERSATION_COUNT, batchSize);
            if (candidates.isEmpty()) return;

            log.debug("메모리 압축 대상 {}건 발견 - actor: {}", candidates.size(), actorId.value());
            for (ConversationHistory conversation : candidates) {
                try {
                    String summary = summarizer.summarize(
                            conversation.getUserMessage(), conversation.getAiResponse());
                    if (summary == null || summary.isBlank()) {
                        throw new IllegalStateException("memory summarizer returned an empty result");
                    }
                    conversation.setSummary(summary.trim());
                    conversation.setSummarized(true);
                    repository.save(conversation);
                    increment("summarized");
                } catch (RuntimeException failure) {
                    completedBatch = false;
                    increment("failed");
                    log.error("메모리 압축 실패 - conversation: {}", conversation.getId(), failure);
                }
            }

            // Bound each turn, then yield to other actors. A full successful batch may have more
            // durable work behind it, so enqueue one continuation at the tail of the same queue.
            if (completedBatch && candidates.size() == batchSize) {
                enqueue(actorId);
            }
        } catch (RuntimeException failure) {
            increment("failed");
            log.error("메모리 압축 조회 실패 - actor: {}", actorId.value(), failure);
        }
    }

    private void increment(String outcome) {
        meterRegistry.counter(METRIC_NAME, "outcome", outcome).increment();
    }
}
