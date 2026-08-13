package com.gahyeonbot.services.ai.agent;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.entity.AgentRun;
import com.gahyeonbot.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AgentControlService {
    private final AgentRunRepository runRepository;
    private final AgentApprovalService approvalService;
    private final AgentRunLedger ledger;
    private final AgentRuntime runtime;

    @Transactional(readOnly = true)
    public AgentRunView latest(ActorId actorId) {
        AgentRun run = runRepository.findFirstByActorIdOrderByCreatedAtDesc(actorId.value())
                .orElseThrow(() -> new IllegalArgumentException("에이전트 실행 기록이 없습니다."));
        return view(run, actorId);
    }

    @Transactional(readOnly = true)
    public AgentRunView get(String runId, ActorId actorId) {
        AgentRun run = owned(runId, actorId);
        return view(run, actorId);
    }

    public AgentResult approveAndResume(String approvalId, ActorId actorId) {
        var approval = approvalService.decide(approvalId, actorId, true);
        ledger.appendToolEvent(approval.getRun().getId(), AgentEventType.APPROVAL_RESOLVED,
                approval.getToolName(), "approved by " + actorId.value());
        return runtime.resume(approval.getRun().getId(), actorId);
    }

    public AgentRunView reject(String approvalId, ActorId actorId) {
        var approval = approvalService.decide(approvalId, actorId, false);
        String runId = approval.getRun().getId();
        ledger.appendToolEvent(runId, AgentEventType.APPROVAL_RESOLVED,
                approval.getToolName(), "rejected by " + actorId.value());
        AgentRun cancelled = ledger.cancel(runId, actorId, "approval rejected");
        return view(cancelled, actorId);
    }

    public AgentRunView cancel(String runId, ActorId actorId) {
        return view(ledger.cancel(runId, actorId, "cancelled by user"), actorId);
    }

    private AgentRun owned(String runId, ActorId actorId) {
        AgentRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("실행을 찾을 수 없습니다: " + runId));
        if (run.getActorId() != actorId.value()) {
            throw new SecurityException("이 실행을 조회할 권한이 없습니다.");
        }
        return run;
    }

    private AgentRunView view(AgentRun run, ActorId actorId) {
        var approvals = approvalService.list(run.getId(), actorId).stream()
                .map(value -> new AgentRunView.ApprovalView(
                        value.getId(), value.getToolName(), value.getStatus()))
                .toList();
        return new AgentRunView(
                run.getId(),
                run.getStatus(),
                run.getCurrentStep(),
                run.getMaxSteps(),
                run.getInputText(),
                run.getOutputText(),
                run.getErrorCode(),
                run.getUpdatedAt(),
                approvals);
    }
}
