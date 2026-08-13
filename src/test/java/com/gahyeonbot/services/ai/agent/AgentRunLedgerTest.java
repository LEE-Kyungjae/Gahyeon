package com.gahyeonbot.services.ai.agent;

import com.gahyeonbot.entity.AgentRun;
import com.gahyeonbot.repository.AgentRunRepository;
import com.gahyeonbot.repository.AgentRunEventRepository;
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
@Import(AgentRunLedger.class)
class AgentRunLedgerTest {
    @Autowired AgentRunLedger ledger;
    @Autowired AgentRunEventRepository eventRepository;
    @Autowired AgentRunRepository runRepository;

    @Test
    void persistsRunLifecycleAndOrderedEvents() {
        AgentRun created = ledger.create(request("discord-interaction-1"));
        AgentRun running = ledger.transition(
                created.getId(), AgentRunStatus.RUNNING, AgentEventType.RUN_STARTED, null);
        ledger.advanceStep(running.getId(), AgentEventType.MODEL_CALL_STARTED, "model=test");
        ledger.appendToolEvent(
                running.getId(), AgentEventType.TOOL_CALL_COMPLETED, "get_weather", "ok");
        AgentRun succeeded = ledger.succeed(running.getId(), "맑아");

        assertThat(succeeded.getStatus()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(succeeded.getOutputText()).isEqualTo("맑아");
        assertThat(succeeded.getCurrentStep()).isEqualTo(1);
        assertThat(eventRepository.findByRunIdOrderBySequenceAsc(running.getId()))
                .extracting(event -> event.getEventType())
                .containsExactly(
                        AgentEventType.RUN_CREATED,
                        AgentEventType.RUN_STARTED,
                        AgentEventType.MODEL_CALL_STARTED,
                        AgentEventType.TOOL_CALL_COMPLETED,
                        AgentEventType.RUN_SUCCEEDED);
    }

    @Test
    void requestIdIsIdempotent() {
        AgentRun first = ledger.create(request("same-request"));
        AgentRun second = ledger.create(request("same-request"));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(eventRepository.findByRunIdOrderBySequenceAsc(first.getId())).hasSize(1);
    }

    @Test
    void rejectsInvalidTransition() {
        AgentRun run = ledger.create(request("invalid-transition"));

        assertThatThrownBy(() -> ledger.succeed(run.getId(), "no"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUEUED -> SUCCEEDED");
    }

    @Test
    void enforcesStepLimit() {
        AgentRun run = ledger.create(new AgentRunRequest(
                "step-limit", "text:1", AgentModality.TEXT, 10L,
                new com.gahyeonbot.core.identity.ActorId(1L), "tester", "질문", 1));
        ledger.transition(run.getId(), AgentRunStatus.RUNNING, AgentEventType.RUN_STARTED, null);
        ledger.advanceStep(run.getId(), AgentEventType.MODEL_CALL_STARTED, null);

        assertThatThrownBy(() -> ledger.advanceStep(
                run.getId(), AgentEventType.MODEL_CALL_STARTED, null))
                .isInstanceOf(AgentRunLedger.StepLimitExceededException.class);
    }

    @Test
    void terminalCancellationRejectsLaterToolEventsAndRemainsTheLastEvent() {
        AgentRun run = ledger.create(request("cancelled-terminal"));
        ledger.transition(run.getId(), AgentRunStatus.RUNNING, AgentEventType.RUN_STARTED, null);
        ledger.cancel(run.getId(), new com.gahyeonbot.core.identity.ActorId(1L), "superseded");

        assertThatThrownBy(() -> ledger.appendToolEvent(
                run.getId(), AgentEventType.TOOL_CALL_COMPLETED, "get_weather", "late"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED");
        assertThat(eventRepository.findByRunIdOrderBySequenceAsc(run.getId()))
                .extracting(event -> event.getEventType())
                .endsWith(AgentEventType.RUN_CANCELLED);
    }

    @Test
    void replacementCancelsOnlyOlderInteractiveRunsForTheSameActor() {
        AgentRun running = running("old-running");
        AgentRun waitingApproval = running("old-approval");
        ledger.transition(waitingApproval.getId(), AgentRunStatus.WAITING_APPROVAL,
                AgentEventType.APPROVAL_REQUESTED, "approval");
        AgentRun waitingBackground = running("old-background");
        ledger.transition(waitingBackground.getId(), AgentRunStatus.WAITING_BACKGROUND,
                AgentEventType.BACKGROUND_WAIT_STARTED, "background");
        AgentRun otherActor = ledger.create(new AgentRunRequest(
                "other-actor", "text:2", AgentModality.TEXT, 10L,
                new com.gahyeonbot.core.identity.ActorId(2L), "other", "질문", 8));
        ledger.transition(otherActor.getId(), AgentRunStatus.RUNNING,
                AgentEventType.RUN_STARTED, null);
        AgentRun replacement = ledger.create(request("replacement"));

        assertThat(ledger.startInteractiveRun(
                replacement.getId(),
                new com.gahyeonbot.core.identity.ActorId(1L),
                "client_generation_changed").getStatus())
                .isEqualTo(AgentRunStatus.RUNNING);

        assertThat(runRepository.findById(running.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(runRepository.findById(waitingApproval.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(runRepository.findById(waitingBackground.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.WAITING_BACKGROUND);
        assertThat(runRepository.findById(otherActor.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.RUNNING);
        assertThat(runRepository.findById(replacement.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void retryingAnOlderRequestCannotCancelANewerRun() {
        AgentRun older = running("old-idempotent");
        AgentRun newer = running("newer-run");

        AgentRun idempotentRetry = ledger.create(request("old-idempotent"));
        assertThat(idempotentRetry.getId()).isEqualTo(older.getId());
        assertThatThrownBy(() -> ledger.startInteractiveRun(
                idempotentRetry.getId(),
                new com.gahyeonbot.core.identity.ActorId(1L),
                "client_generation_changed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING");
        assertThat(runRepository.findById(newer.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void replacementCancelsAnOlderRunThatHasNotStartedYet() {
        AgentRun queued = ledger.create(request("old-queued"));
        AgentRun replacement = ledger.create(request("replacement-after-queued"));

        ledger.startInteractiveRun(
                replacement.getId(),
                new com.gahyeonbot.core.identity.ActorId(1L),
                "client_generation_changed");

        assertThat(runRepository.findById(queued.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(runRepository.findById(replacement.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void onlyOneConcurrentResumerCanClaimAPausedRun() throws Exception {
        AgentRun paused = running("concurrent-resume");
        ledger.transition(
                paused.getId(),
                AgentRunStatus.WAITING_BACKGROUND,
                AgentEventType.BACKGROUND_WAIT_STARTED,
                "job");
        TestTransaction.flagForCommit();
        TestTransaction.end();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var claims = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            ledger.claimResume(
                                    paused.getId(),
                                    AgentRunStatus.WAITING_BACKGROUND,
                                    AgentEventType.BACKGROUND_RESULT_RECEIVED,
                                    "result");
                            successes.incrementAndGet();
                        } catch (IllegalStateException alreadyClaimed) {
                            rejected.incrementAndGet();
                        }
                        return null;
                    }))
                    .toList();
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var claim : claims) claim.get(5, TimeUnit.SECONDS);
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(runRepository.findById(paused.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentRunStatus.RUNNING);
        assertThat(eventRepository.findByRunIdOrderBySequenceAsc(paused.getId()))
                .filteredOn(event -> event.getEventType() == AgentEventType.BACKGROUND_RESULT_RECEIVED)
                .hasSize(1);
    }

    private AgentRun running(String requestId) {
        AgentRun run = ledger.create(request(requestId));
        return ledger.transition(run.getId(), AgentRunStatus.RUNNING,
                AgentEventType.RUN_STARTED, null);
    }

    private static AgentRunRequest request(String requestId) {
        return new AgentRunRequest(
                requestId, "text:1", AgentModality.TEXT, 10L,
                new com.gahyeonbot.core.identity.ActorId(1L), "tester", "날씨 알려줘", 8);
    }
}
