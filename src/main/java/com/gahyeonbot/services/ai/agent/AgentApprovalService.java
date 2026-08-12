package com.gahyeonbot.services.ai.agent;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.entity.AgentApproval;
import com.gahyeonbot.entity.AgentRun;
import com.gahyeonbot.repository.AgentApprovalRepository;
import com.gahyeonbot.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentApprovalService {
    private final AgentApprovalRepository approvalRepository;
    private final AgentRunRepository runRepository;

    @Transactional
    public AgentApproval request(String runId, String toolName, String arguments) {
        String hash = hash(arguments);
        return approvalRepository.findByRunIdAndToolNameAndArgumentHash(runId, toolName, hash)
                .orElseGet(() -> {
                    AgentRun run = runRepository.findById(runId)
                            .orElseThrow(() -> new IllegalArgumentException("실행을 찾을 수 없습니다: " + runId));
                    AgentApproval created = AgentApproval.builder()
                            .id(UUID.randomUUID().toString())
                            .run(run)
                            .toolName(toolName)
                            .toolArguments(arguments)
                            .argumentHash(hash)
                            .status(AgentApprovalStatus.PENDING)
                            .requestedAt(LocalDateTime.now())
                            .build();
                    try {
                        return approvalRepository.saveAndFlush(created);
                    } catch (DataIntegrityViolationException duplicate) {
                        return approvalRepository
                                .findByRunIdAndToolNameAndArgumentHash(runId, toolName, hash)
                                .orElseThrow(() -> duplicate);
                    }
                });
    }

    @Transactional
    public AgentApproval decide(String approvalId, ActorId actorId, boolean approve) {
        // Resolve the run as a scalar so the approval entity does not enter the persistence context
        // before it is locked. Keep the global order run -> approval for decisions.
        String runId = approvalRepository.findRunIdById(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("승인 요청을 찾을 수 없습니다."));
        AgentRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalArgumentException("실행을 찾을 수 없습니다."));
        AgentApproval approval = approvalRepository.findByIdForUpdate(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("승인 요청을 찾을 수 없습니다."));
        assertOwner(run, actorId);
        if (run.getStatus() != AgentRunStatus.WAITING_APPROVAL) {
            throw new IllegalStateException(
                    "승인 대기 실행만 결정할 수 있습니다: " + run.getStatus());
        }
        if (approval.getStatus() != AgentApprovalStatus.PENDING) {
            throw new IllegalStateException("이미 처리된 승인 요청입니다: " + approval.getStatus());
        }
        approval.setStatus(approve ? AgentApprovalStatus.APPROVED : AgentApprovalStatus.REJECTED);
        approval.setDecidedAt(LocalDateTime.now());
        approval.setDecidedBy(actorId.value());
        return approval;
    }

    @Transactional
    public boolean consumeIfApproved(String runId, String toolName, String arguments) {
        AgentApproval approval = approvalRepository
                .findByCallForUpdate(runId, toolName, hash(arguments))
                .orElse(null);
        if (approval == null || approval.getStatus() != AgentApprovalStatus.APPROVED) return false;
        approval.setStatus(AgentApprovalStatus.CONSUMED);
        approval.setConsumedAt(LocalDateTime.now());
        return true;
    }

    @Transactional(readOnly = true)
    public boolean hasApproved(String runId) {
        return approvalRepository.findFirstByRunIdAndStatusOrderByRequestedAtAsc(
                runId, AgentApprovalStatus.APPROVED).isPresent();
    }

    @Transactional(readOnly = true)
    public List<AgentApproval> list(String runId, ActorId actorId) {
        AgentRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("실행을 찾을 수 없습니다: " + runId));
        assertOwner(run, actorId);
        return approvalRepository.findByRunIdOrderByRequestedAtAsc(runId);
    }

    private static void assertOwner(AgentRun run, ActorId actorId) {
        if (run.getActorId() != actorId.value()) {
            throw new SecurityException("이 실행을 제어할 권한이 없습니다.");
        }
    }

    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", impossible);
        }
    }
}
