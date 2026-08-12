package com.gahyeonbot.services.ai.agent;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.entity.AgentRun;
import com.gahyeonbot.entity.AgentRunEvent;
import com.gahyeonbot.entity.AgentSession;
import com.gahyeonbot.repository.AgentRunEventRepository;
import com.gahyeonbot.repository.AgentRunRepository;
import com.gahyeonbot.repository.AgentSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentRunLedger {
    private static final Map<AgentRunStatus, EnumSet<AgentRunStatus>> TRANSITIONS = transitions();

    private final AgentSessionRepository sessionRepository;
    private final AgentRunRepository runRepository;
    private final AgentRunEventRepository eventRepository;

    @Transactional
    public AgentRun create(AgentRunRequest request) {
        AgentRun existing = runRepository.findByRequestId(request.requestId()).orElse(null);
        if (existing != null) return existing;

        AgentSession session = getOrCreateSession(request);
        LocalDateTime now = LocalDateTime.now();
        AgentRun run = AgentRun.builder()
                .id(UUID.randomUUID().toString())
                .requestId(request.requestId())
                .session(session)
                .modality(request.modality())
                .toolScopeId(request.toolScopeId())
                .actorId(request.actorId().value())
                .actorDisplayName(request.actorDisplayName())
                .inputText(request.input())
                .status(AgentRunStatus.QUEUED)
                .currentStep(0)
                .maxSteps(request.maxSteps())
                .nextEventSequence(1)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            run = runRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException duplicate) {
            return runRepository.findByRequestId(request.requestId()).orElseThrow(() -> duplicate);
        }
        appendLocked(run, AgentEventType.RUN_CREATED, null, null);
        return run;
    }

    @Transactional
    public AgentRun transition(
            String runId,
            AgentRunStatus target,
            AgentEventType eventType,
            String payload) {
        AgentRun run = locked(runId);
        AgentRunStatus current = run.getStatus();
        if (current == target) return run;
        if (!TRANSITIONS.getOrDefault(current, EnumSet.noneOf(AgentRunStatus.class)).contains(target)) {
            throw new IllegalStateException("허용되지 않은 agent run 상태 전이: " + current + " -> " + target);
        }
        LocalDateTime now = LocalDateTime.now();
        run.setStatus(target);
        run.setUpdatedAt(now);
        if (target == AgentRunStatus.RUNNING && run.getStartedAt() == null) run.setStartedAt(now);
        if (target.terminal()) run.setCompletedAt(now);
        appendLocked(run, eventType, null, payload);
        return run;
    }

    @Transactional
    public AgentRun advanceStep(String runId, AgentEventType eventType, String payload) {
        AgentRun run = locked(runId);
        if (run.getStatus() != AgentRunStatus.RUNNING) {
            throw new IllegalStateException("RUNNING 상태에서만 step을 진행할 수 있습니다.");
        }
        if (run.getCurrentStep() >= run.getMaxSteps()) {
            throw new StepLimitExceededException(runId, run.getMaxSteps());
        }
        run.setCurrentStep(run.getCurrentStep() + 1);
        run.setUpdatedAt(LocalDateTime.now());
        appendLocked(run, eventType, null, payload);
        return run;
    }

    @Transactional
    public void appendToolEvent(
            String runId,
            AgentEventType eventType,
            String toolName,
            String payload) {
        AgentRun run = locked(runId);
        if (run.getStatus().terminal()) {
            throw new IllegalStateException(
                    "종료된 agent run에는 이벤트를 추가할 수 없습니다: " + run.getStatus());
        }
        appendLocked(run, eventType, toolName, payload);
    }

    /**
     * Cancels older interactive work for the same actor after a replacement run is persisted.
     * Background continuations deliberately remain independent.
     */
    @Transactional
    public AgentRun startInteractiveRun(
            String runId,
            ActorId actorId,
            String reason) {
        AgentRun current = locked(runId);
        if (current.getActorId() != actorId.value()) {
            throw new SecurityException("이 실행을 대체할 권한이 없습니다.");
        }
        if (current.getStatus() != AgentRunStatus.QUEUED) {
            throw new IllegalStateException(
                    "QUEUED 상태의 agent run만 시작할 수 있습니다: " + current.getStatus());
        }
        List<AgentRun> superseded = runRepository.findSupersededForUpdate(
                actorId.value(),
                current.getCreatedAt(),
                List.of(
                        AgentRunStatus.QUEUED,
                        AgentRunStatus.RUNNING,
                        AgentRunStatus.WAITING_APPROVAL));
        superseded.forEach(run -> transitionLocked(
                run, AgentRunStatus.CANCELLED, AgentEventType.RUN_CANCELLED, reason));
        return transitionLocked(
                current, AgentRunStatus.RUNNING, AgentEventType.RUN_STARTED, null);
    }

    /**
     * Atomically claims a paused run for one resumer.
     *
     * <p>This deliberately differs from the idempotent general transition method: only the caller
     * that observes {@code expected} while holding the run row lock may enter the resumed loop.</p>
     */
    @Transactional
    public AgentRun claimResume(
            String runId,
            AgentRunStatus expected,
            AgentEventType eventType,
            String payload) {
        AgentRun run = locked(runId);
        if (run.getStatus() != expected) {
            throw new IllegalStateException(
                    "재개할 수 없는 agent run 상태입니다: expected=" + expected
                            + ", actual=" + run.getStatus());
        }
        return transitionLocked(run, AgentRunStatus.RUNNING, eventType, payload);
    }

    @Transactional
    public AgentRun succeed(String runId, String output) {
        AgentRun run = locked(runId);
        run.setOutputText(output);
        return transitionLocked(run, AgentRunStatus.SUCCEEDED, AgentEventType.RUN_SUCCEEDED, null);
    }

    @Transactional
    public AgentRun fail(String runId, String errorCode, String errorMessage) {
        AgentRun run = locked(runId);
        run.setErrorCode(errorCode);
        run.setErrorMessage(errorMessage);
        return transitionLocked(run, AgentRunStatus.FAILED, AgentEventType.RUN_FAILED, errorCode);
    }

    @Transactional
    public AgentRun cancel(String runId, ActorId actorId, String reason) {
        AgentRun run = locked(runId);
        if (run.getActorId() != actorId.value()) {
            throw new SecurityException("이 실행을 취소할 권한이 없습니다.");
        }
        if (run.getStatus().terminal()) return run;
        return transitionLocked(run, AgentRunStatus.CANCELLED, AgentEventType.RUN_CANCELLED, reason);
    }

    private AgentRun transitionLocked(
            AgentRun run,
            AgentRunStatus target,
            AgentEventType eventType,
            String payload) {
        if (!TRANSITIONS.getOrDefault(run.getStatus(), EnumSet.noneOf(AgentRunStatus.class)).contains(target)) {
            throw new IllegalStateException("허용되지 않은 agent run 상태 전이: " + run.getStatus() + " -> " + target);
        }
        LocalDateTime now = LocalDateTime.now();
        run.setStatus(target);
        run.setUpdatedAt(now);
        if (target.terminal()) run.setCompletedAt(now);
        appendLocked(run, eventType, null, payload);
        return run;
    }

    private AgentSession getOrCreateSession(AgentRunRequest request) {
        return sessionRepository.findBySessionKey(request.sessionKey()).orElseGet(() -> {
            LocalDateTime now = LocalDateTime.now();
            AgentSession created = AgentSession.builder()
                    .id(UUID.randomUUID().toString())
                    .sessionKey(request.sessionKey())
                    .modality(request.modality())
                    .toolScopeId(request.toolScopeId())
                    .actorId(request.actorId().value())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            try {
                return sessionRepository.saveAndFlush(created);
            } catch (DataIntegrityViolationException duplicate) {
                return sessionRepository.findBySessionKey(request.sessionKey()).orElseThrow(() -> duplicate);
            }
        });
    }

    private AgentRun locked(String runId) {
        return runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalArgumentException("agent run을 찾을 수 없습니다: " + runId));
    }

    private void appendLocked(AgentRun run, AgentEventType type, String toolName, String payload) {
        long sequence = run.getNextEventSequence();
        run.setNextEventSequence(sequence + 1);
        run.setUpdatedAt(LocalDateTime.now());
        eventRepository.save(AgentRunEvent.builder()
                .run(run)
                .sequence(sequence)
                .eventType(type)
                .step(run.getCurrentStep())
                .toolName(toolName)
                .payload(payload)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private static Map<AgentRunStatus, EnumSet<AgentRunStatus>> transitions() {
        Map<AgentRunStatus, EnumSet<AgentRunStatus>> result = new EnumMap<>(AgentRunStatus.class);
        result.put(AgentRunStatus.QUEUED, EnumSet.of(
                AgentRunStatus.RUNNING, AgentRunStatus.CANCELLED, AgentRunStatus.FAILED));
        result.put(AgentRunStatus.RUNNING, EnumSet.of(
                AgentRunStatus.WAITING_APPROVAL, AgentRunStatus.WAITING_BACKGROUND,
                AgentRunStatus.SUCCEEDED, AgentRunStatus.FAILED, AgentRunStatus.CANCELLED));
        result.put(AgentRunStatus.WAITING_APPROVAL, EnumSet.of(
                AgentRunStatus.RUNNING, AgentRunStatus.FAILED, AgentRunStatus.CANCELLED));
        result.put(AgentRunStatus.WAITING_BACKGROUND, EnumSet.of(
                AgentRunStatus.RUNNING, AgentRunStatus.FAILED, AgentRunStatus.CANCELLED));
        return Map.copyOf(result);
    }

    public static class StepLimitExceededException extends RuntimeException {
        public StepLimitExceededException(String runId, int maxSteps) {
            super("agent run step limit 초과: runId=" + runId + ", maxSteps=" + maxSteps);
        }
    }
}
