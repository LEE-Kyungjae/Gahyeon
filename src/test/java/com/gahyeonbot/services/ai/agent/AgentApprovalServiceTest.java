package com.gahyeonbot.services.ai.agent;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.entity.AgentApproval;
import com.gahyeonbot.entity.AgentRun;
import com.gahyeonbot.repository.AgentApprovalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.transaction.TestTransaction;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({AgentRunLedger.class, AgentApprovalService.class})
class AgentApprovalServiceTest {
    @Autowired AgentRunLedger ledger;
    @Autowired AgentApprovalService approvals;
    @Autowired AgentApprovalRepository approvalRepository;

    @Test
    void ownerCanApproveAndRuntimeConsumesExactlyOnce() {
        AgentRun run = waitingRun();
        AgentApproval request = approvals.request(run.getId(), "write_calendar", "{\"title\":\"회의\"}");

        approvals.decide(request.getId(), new ActorId(1L), true);

        assertThat(approvals.consumeIfApproved(
                run.getId(), "write_calendar", "{\"title\":\"회의\"}")).isTrue();
        assertThat(approvals.consumeIfApproved(
                run.getId(), "write_calendar", "{\"title\":\"회의\"}")).isFalse();
        assertThat(approvalRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentApprovalStatus.CONSUMED);
    }

    @Test
    void anotherUserCannotDecideApproval() {
        AgentRun run = waitingRun();
        AgentApproval request = approvals.request(run.getId(), "write_calendar", "{}");

        assertThatThrownBy(() -> approvals.decide(request.getId(), new ActorId(999L), true))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void cancelRequiresRunOwner() {
        AgentRun run = waitingRun();

        assertThatThrownBy(() -> ledger.cancel(run.getId(), new ActorId(999L), "no"))
                .isInstanceOf(SecurityException.class);
        assertThat(ledger.cancel(run.getId(), new ActorId(1L), "user").getStatus())
                .isEqualTo(AgentRunStatus.CANCELLED);
    }

    @Test
    void cancelledRunApprovalCannotBeDecided() {
        AgentRun run = waitingRun();
        AgentApproval request = approvals.request(run.getId(), "write_calendar", "{}");
        ledger.cancel(run.getId(), new ActorId(1L), "superseded");

        assertThatThrownBy(() -> approvals.decide(request.getId(), new ActorId(1L), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED");
        assertThat(approvalRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentApprovalStatus.PENDING);
    }

    @Test
    void concurrentConsumersCanUseOneApprovalOnlyOnce() throws Exception {
        AgentRun run = waitingRun();
        AgentApproval request = approvals.request(run.getId(), "write_calendar", "{}");
        approvals.decide(request.getId(), new ActorId(1L), true);
        TestTransaction.flagForCommit();
        TestTransaction.end();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger consumed = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var consumers = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        if (approvals.consumeIfApproved(run.getId(), "write_calendar", "{}")) {
                            consumed.incrementAndGet();
                        }
                        return null;
                    }))
                    .toList();
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var consumer : consumers) consumer.get(5, TimeUnit.SECONDS);
        }

        assertThat(consumed.get()).isEqualTo(1);
        assertThat(approvalRepository.findById(request.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentApprovalStatus.CONSUMED);
    }

    @Test
    void concurrentApprovalDecisionsHaveOneWinner() throws Exception {
        AgentRun run = waitingRun();
        AgentApproval request = approvals.request(run.getId(), "write_calendar", "{}");
        TestTransaction.flagForCommit();
        TestTransaction.end();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger decided = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var decisions = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            approvals.decide(request.getId(), new ActorId(1L), index == 0);
                            decided.incrementAndGet();
                        } catch (IllegalStateException alreadyDecided) {
                            rejected.incrementAndGet();
                        }
                        return null;
                    }))
                    .toList();
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var decision : decisions) decision.get(5, TimeUnit.SECONDS);
        }

        assertThat(decided.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(approvalRepository.findById(request.getId()).orElseThrow().getStatus())
                .isIn(AgentApprovalStatus.APPROVED, AgentApprovalStatus.REJECTED);
    }

    private AgentRun waitingRun() {
        AgentRun run = ledger.create(new AgentRunRequest(
                "approval-" + java.util.UUID.randomUUID(),
                "text:1",
                AgentModality.TEXT,
                10L,
                new com.gahyeonbot.core.identity.ActorId(1L),
                "tester",
                "일정 등록",
                8));
        ledger.transition(run.getId(), AgentRunStatus.RUNNING, AgentEventType.RUN_STARTED, null);
        return ledger.transition(run.getId(), AgentRunStatus.WAITING_APPROVAL,
                AgentEventType.APPROVAL_REQUESTED, "test");
    }
}
