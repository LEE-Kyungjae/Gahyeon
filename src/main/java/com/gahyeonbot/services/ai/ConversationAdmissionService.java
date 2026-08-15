package com.gahyeonbot.services.ai;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.application.conversation.ContentSafetyPort;
import com.gahyeonbot.core.conversation.AdmissionDecision;
import com.gahyeonbot.core.conversation.AdmissionFacts;
import com.gahyeonbot.core.conversation.ConversationAdmissionPolicy;
import com.gahyeonbot.core.conversation.ConversationReadiness;
import com.gahyeonbot.entity.ModelUsage;
import com.gahyeonbot.repository.ModelUsageRepository;
import com.gahyeonbot.services.ai.agent.AgentModality;
import com.gahyeonbot.services.ai.agent.AgentApprovalRequiredException;
import com.gahyeonbot.services.ai.agent.AgentRequest;
import com.gahyeonbot.services.ai.agent.AgentResult;
import com.gahyeonbot.services.ai.agent.AgentRuntime;
import com.gahyeonbot.services.ai.agent.AgentStreamObserver;
import com.gahyeonbot.services.ai.agent.AgentStreamCancelledException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;

/**
 * Provider-independent conversation admission, usage ledger, and agent execution service.
 *
 * 비용 절감 및 보안 전략:
 * 1. ContentSafetyPort: 교체 가능한 외부 입력 안전성 검사
 * 2. 키워드 필터: 공백/특수문자 우회 방지 (보조 방어선)
 * 3. Rate Limiting: 사용자당 1시간 75회, 하루 100회 제한
 * 4. 봇 전체 제한: 하루 100회, 월 3,100회
 * 5. 중복 차단: 10초 내 재요청 차단
 * 6. DB 로깅: 모든 요청 기록 및 비용 추적
 *
 * @author Gahyeon Team
 * @version 2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationAdmissionService implements ConversationReadiness {

    private final ModelUsageRepository usageRepository;
    private final AgentRuntime agentRuntime;
    private final ConversationAdmissionPolicy admissionPolicy;
    private final ContentSafetyPort contentSafety;
    private final MeterRegistry meterRegistry;

    // Admission DB checks/reservation only. Provider I/O must never run under this lock.
    private final ActorLockRegistry actorLocks = new ActorLockRegistry(256);
    private final ConcurrentHashMap<Long, ConversationExecutionLease> activeExecutions =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> latestAttempts = new ConcurrentHashMap<>();
    private final AtomicLong nextAttempt = new AtomicLong();

    private static final int DUPLICATE_CHECK_SECONDS = 10;    // 중복 요청 차단 시간

    /**
     * 사용자 질문에 대해 AI 응답을 생성합니다.
     * 모든 Rate Limiting 및 보안 검사를 통과해야 합니다.
     *
     * 다중 인스턴스 대응:
     * - Interaction ID 기반 중복 방지 (여러 봇 인스턴스 병렬 실행 시)
     * - DB UNIQUE 제약조건으로 첫 번째 인스턴스만 처리
     *
     * 동일 actor의 quota/idempotency 예약은 짧은 임계구역에서 직렬화한다. 실제 모델 호출은
     * 임계구역 밖에서 실행되며, 더 최신 요청은 이전 실행을 cooperative cancellation 상태로 만든다.
     *
     * @param requestId client request ID (중복 방지용)
     * @param sessionId platform-neutral conversation session ID
     * @param modality conversation modality used by the agent runtime
     * @param actorId Gahyeon 내부 Actor ID
     * @param actorDisplayName Actor 표시 이름
     * @param toolScopeId optional numeric scope used by legacy tool adapters
     * @param userMessage 사용자의 질문 또는 메시지
     * @return AI의 응답 텍스트
     * @throws RateLimitException Rate Limit 초과 시
     * @throws AdversarialPromptException 적대적 프롬프트 감지 시
     */
    public AgentResult chatResult(
            String requestId,
            String sessionId,
            AgentModality modality,
            ActorId actorId,
            String actorDisplayName,
            Long toolScopeId,
            String userMessage) throws RateLimitException, AdversarialPromptException {
        return chatInternal(
                requestId, sessionId, modality, actorId, actorDisplayName,
                toolScopeId, userMessage, null);
    }

    public AgentResult chatResultStreaming(
            String requestId,
            String sessionId,
            AgentModality modality,
            ActorId actorId,
            String actorDisplayName,
            Long toolScopeId,
            String userMessage,
            AgentStreamObserver observer) throws RateLimitException, AdversarialPromptException {
        Objects.requireNonNull(observer, "stream observer");
        return chatInternal(
                requestId, sessionId, modality, actorId, actorDisplayName,
                toolScopeId, userMessage, observer);
    }

    private AgentResult chatInternal(
            String requestId,
            String sessionId,
            AgentModality modality,
            ActorId actorId,
            String actorDisplayName,
            Long toolScopeId,
            String userMessage,
            AgentStreamObserver observer) throws RateLimitException, AdversarialPromptException {
        long attempt = beginAttempt(actorId);
        if (!isReady()) {
            latestAttempts.remove(actorId.value(), attempt);
            throw new RateLimitException("Gahyeon AI 서비스가 비활성화되어 있습니다.");
        }

        boolean moderationFlagged = false;
        Timer.Sample safetyTimer = Timer.start(meterRegistry);
        String safetyOutcome = "unavailable";
        try {
            ContentSafetyPort.Decision safety = contentSafety.evaluate(userMessage);
            moderationFlagged = safety == ContentSafetyPort.Decision.UNSAFE;
            safetyOutcome = safety.name().toLowerCase(Locale.ROOT);
        } catch (RuntimeException unavailable) {
            safetyOutcome = "failure";
            log.warn("Content safety provider 실패 - 결정론적 local policy로 대체: {}",
                    unavailable.getMessage());
        } finally {
            safetyTimer.stop(meterRegistry.timer(
                    "gahyeonbot.content.safety.latency", "outcome", safetyOutcome));
        }

        ExecutionAdmission execution;
        try {
            execution = reserveExecution(
                    requestId, actorId, actorDisplayName, toolScopeId, userMessage,
                    moderationFlagged, observer, attempt);
        } catch (RuntimeException | RateLimitException | AdversarialPromptException failure) {
            latestAttempts.remove(actorId.value(), attempt);
            throw failure;
        }
        ConversationExecutionLease lease = execution.lease();
        Thread runner = Thread.currentThread();
        lease.bindRunner(runner);

        // 공통 에이전트 런타임 호출
        try {
            log.info("에이전트 요청 시작 - actor: {}, 메시지 길이: {} 문자", actorDisplayName, userMessage.length());
            AgentRequest agentRequest = new AgentRequest(
                    requestId,
                    sessionId,
                    modality,
                    toolScopeId,
                    actorId,
                    actorDisplayName,
                    userMessage,
                    8);
            AgentResult result = observer == null
                    ? agentRuntime.execute(agentRequest, lease)
                    : agentRuntime.executeStreaming(
                            agentRequest, lease.streamingObserver(), lease);
            String response = result.content();
            log.info("에이전트 응답 성공 - run={}, 사용자={}, 도구={}, {}ms",
                    result.runId(), actorDisplayName, result.tools(), result.duration().toMillis());

            if (!lease.complete(() -> finishUsage(execution.usage(), response, true, null))) {
                throw new AgentStreamCancelledException();
            }

            return result;

        } catch (AgentStreamCancelledException cancelled) {
            lease.cancel();
            log.info("에이전트 요청 취소 - actor={}", actorDisplayName);
            throw cancelled;
        } catch (AgentApprovalRequiredException approvalRequired) {
            if (!lease.complete(() -> finishUsage(
                    execution.usage(), null, false, "WAITING_APPROVAL"))) {
                throw new AgentStreamCancelledException(approvalRequired);
            }
            log.info("에이전트 승인 대기 - run={}, tool={}",
                    approvalRequired.getRunId(), approvalRequired.getToolName());
            throw approvalRequired;
        } catch (Exception e) {
            if (lease.isCancelled()) {
                lease.cancel();
                throw new AgentStreamCancelledException(e);
            }
            log.error("AI 처리 실패 - actor: {}, 메시지: {}", actorDisplayName, userMessage, e);
            lease.complete(() -> finishUsage(
                    execution.usage(), null, false, e.getMessage()));
            throw new ChatProcessingException(ChatProcessingException.ErrorType.AI_PROVIDER_FAILURE,
                    "AI 응답을 받지 못했습니다. 잠시 후 다시 시도해주세요.", e);
        } finally {
            lease.unbindRunner(runner);
            activeExecutions.remove(actorId.value(), lease);
            latestAttempts.remove(actorId.value(), attempt);
        }
    }

    private long beginAttempt(ActorId actorId) {
        long attempt = nextAttempt.incrementAndGet();
        Lock actorLock = actorLocks.lockFor(actorId);
        ConversationExecutionLease.Cancellation superseded;
        actorLock.lock();
        try {
            latestAttempts.put(actorId.value(), attempt);
            ConversationExecutionLease previous = activeExecutions.get(actorId.value());
            superseded = previous == null
                    ? ConversationExecutionLease.Cancellation.NONE
                    : previous.markCancelled();
        } finally {
            actorLock.unlock();
        }
        superseded.notifyCancellation();
        return attempt;
    }

    private ExecutionAdmission reserveExecution(
            String requestId,
            ActorId actorId,
            String actorDisplayName,
            Long toolScopeId,
            String userMessage,
            boolean moderationFlagged,
            AgentStreamObserver observer,
            long attempt) throws RateLimitException, AdversarialPromptException {
        Lock actorLock = actorLocks.lockFor(actorId);
        actorLock.lock();
        ConversationExecutionLease.Cancellation superseded =
                ConversationExecutionLease.Cancellation.NONE;
        try {
            if (!Objects.equals(latestAttempts.get(actorId.value()), attempt)) {
                throw new AgentStreamCancelledException();
            }
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime since = now.minusSeconds(DUPLICATE_CHECK_SECONDS);
            LocalDateTime oneHourAgo = now.minusHours(1);
            LocalDateTime oneDayAgo = now.minusDays(1);
            LocalDateTime monthStart = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
            boolean duplicate = userMessage != null && !userMessage.isBlank()
                    && !usageRepository.findDuplicatePrompt(
                            actorId.value(), userMessage, since).isEmpty();
            long hourlyUsage = usageRepository.countByActorIdAndCreatedAtAfter(
                    actorId.value(), oneHourAgo);
            long dailyUsage = usageRepository.countByActorIdAndCreatedAtAfter(
                    actorId.value(), oneDayAgo);
            long totalDailyUsage = usageRepository.countByCreatedAtAfter(oneDayAgo);
            long monthlyUsage = usageRepository.countMonthlyUsage(monthStart);
            AdmissionDecision decision = admissionPolicy.decide(userMessage, new AdmissionFacts(
                    moderationFlagged, duplicate, hourlyUsage, dailyUsage,
                    totalDailyUsage, monthlyUsage));
            if (!decision.accepted()) {
                rejectAdmission(
                        decision, requestId, actorId, actorDisplayName,
                        toolScopeId, userMessage);
            }

            ModelUsage usage = reserveUsage(
                    requestId, actorId, actorDisplayName, toolScopeId, userMessage);
            ConversationExecutionLease lease = new ConversationExecutionLease(
                    observer,
                    () -> finishUsage(usage, null, false, "CANCELLED"));
            ConversationExecutionLease previous = activeExecutions.get(actorId.value());
            if (previous != null) {
                superseded = previous.markCancelled();
                meterRegistry.counter("gahyeonbot.conversation.supersessions").increment();
            }
            activeExecutions.put(actorId.value(), lease);
            return new ExecutionAdmission(usage, lease);
        } finally {
            actorLock.unlock();
            superseded.notifyCancellation();
        }
    }

    private void rejectAdmission(
            AdmissionDecision decision,
            String requestId,
            ActorId actorId,
            String actorDisplayName,
            Long toolScopeId,
            String userMessage) throws RateLimitException, AdversarialPromptException {
        log.warn("대화 admission 거절 actor={} reason={}", actorDisplayName, decision.reason());
        if (decision.reason() == AdmissionDecision.Reason.INVALID_INPUT) {
            throw new IllegalArgumentException(decision.message());
        }
        if (decision.reason() == AdmissionDecision.Reason.UNSAFE_INPUT) {
            logUsage(requestId, actorId, actorDisplayName, toolScopeId, userMessage, null, false,
                    decision.reason().name());
            throw new AdversarialPromptException(decision.message());
        }
        throw new RateLimitException(decision.message());
    }

    private ModelUsage reserveUsage(
            String requestId,
            ActorId actorId,
            String actorDisplayName,
            Long toolScopeId,
            String prompt) {
        ModelUsage usage = ModelUsage.builder()
                .requestId(requestId)
                .actorId(actorId.value())
                .actorDisplayName(actorDisplayName)
                .toolScopeId(toolScopeId)
                .prompt(prompt)
                .model("agent-runtime")
                .success(false)
                .errorMessage("IN_PROGRESS")
                .createdAt(LocalDateTime.now())
                .build();
        return usageRepository.saveAndFlush(usage);
    }

    private void finishUsage(
            ModelUsage usage,
            String response,
            boolean success,
            String errorMessage) {
        try {
            usage.setResponse(response);
            usage.setSuccess(success);
            usage.setErrorMessage(errorMessage);
            usageRepository.saveAndFlush(usage);
        } catch (Exception failure) {
            log.error("사용량 완료 기록 실패 request={}", usage.getRequestId(), failure);
        }
    }

    /**
     * 사용량을 DB에 로깅합니다.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException 이미 처리된 request_id인 경우
     */
    private void logUsage(
            String requestId,
            ActorId actorId,
            String actorDisplayName,
            Long toolScopeId,
            String prompt,
            String response,
            boolean success,
            String errorMessage) {
        try {
            ModelUsage usage = ModelUsage.builder()
                    .requestId(requestId)
                    .actorId(actorId.value())
                    .actorDisplayName(actorDisplayName)
                    .toolScopeId(toolScopeId)
                    .prompt(prompt)
                    .response(response)
                    .model("agent-runtime")
                    .success(success)
                    .errorMessage(errorMessage)
                    .createdAt(LocalDateTime.now())
                    .build();

            usageRepository.saveAndFlush(usage);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // UNIQUE 제약조건 위반: 다른 인스턴스가 이미 처리 중
            log.warn("중복 request ID 감지 - 다른 인스턴스가 처리 중: {}", requestId);
            throw e;
        } catch (Exception e) {
            log.error("사용량 로깅 실패", e);
        }
    }

    private record ExecutionAdmission(
            ModelUsage usage,
            ConversationExecutionLease lease) {}

    /**
     * 서비스 활성화 상태를 확인합니다.
     */
    @Override
    public boolean isReady() {
        try {
            return agentRuntime.isReady();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Rate Limit 예외
     */
    public static class RateLimitException extends Exception {
        public RateLimitException(String message) {
            super(message);
        }
    }

    /**
     * 적대적 프롬프트 예외
     */
    public static class AdversarialPromptException extends Exception {
        public AdversarialPromptException(String message) {
            super(message);
        }
    }

    /**
     * AI provider 처리 중 발생한 일반 오류
     */
    public static class ChatProcessingException extends RuntimeException {
        public enum ErrorType {
            AI_PROVIDER_FAILURE,
            UNKNOWN
        }

        private final ErrorType errorType;

        public ChatProcessingException(ErrorType errorType, String message, Throwable cause) {
            super(message, cause);
            this.errorType = errorType;
        }

        public ErrorType getErrorType() {
            return errorType;
        }
    }
}
