package com.gahyeonbot.services.ai.agent;

import com.gahyeonbot.entity.AgentRun;
import com.gahyeonbot.repository.AgentBackgroundJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({AgentRunLedger.class, AgentBackgroundQueue.class})
class AgentBackgroundQueueTest {
    @Autowired AgentRunLedger ledger;
    @Autowired AgentBackgroundQueue queue;
    @Autowired AgentBackgroundJobRepository jobs;

    @Test
    void durableJobMovesRunToWaitingAndCanBeClaimed() {
        AgentRun run = runningRun("background-claim");
        var scheduled = queue.schedule(run.getId(), "test", "payload", Duration.ZERO, 3);

        assertThat(queue.claimDue()).contains(new AgentBackgroundClaim(
                scheduled.getId(), run.getId(), "test", "payload"));
        assertThat(jobs.findById(scheduled.getId()).orElseThrow().getStatus())
                .isEqualTo(AgentBackgroundJobStatus.RUNNING);
    }

    @Test
    void failedClaimIsRetriedBeforeTerminalFailure() {
        AgentRun run = runningRun("background-retry");
        var scheduled = queue.schedule(run.getId(), "test", "payload", Duration.ZERO, 2);
        queue.claimDue();

        queue.retryOrFail(scheduled.getId(), "temporary");

        var stored = jobs.findById(scheduled.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AgentBackgroundJobStatus.PENDING);
        assertThat(stored.getAttempts()).isEqualTo(1);
    }

    private AgentRun runningRun(String requestId) {
        AgentRun run = ledger.create(new AgentRunRequest(
                requestId,
                "text:1",
                AgentModality.TEXT,
                10L,
                new com.gahyeonbot.core.identity.ActorId(1L),
                "tester",
                "긴 작업",
                8));
        return ledger.transition(run.getId(), AgentRunStatus.RUNNING,
                AgentEventType.RUN_STARTED, null);
    }
}
