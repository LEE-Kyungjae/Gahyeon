package com.gahyeonbot.services.ai;

import com.gahyeonbot.config.AppCredentialsConfig;
import com.gahyeonbot.core.conversation.AdmissionDecision;
import com.gahyeonbot.core.conversation.AdmissionFacts;
import com.gahyeonbot.core.conversation.ConversationAdmissionPolicy;
import com.gahyeonbot.entity.OpenAiUsage;
import com.gahyeonbot.repository.OpenAiUsageRepository;
import com.gahyeonbot.services.ai.agent.AgentGateway;
import com.gahyeonbot.services.ai.agent.AgentApprovalRequiredException;
import com.gahyeonbot.services.ai.agent.AgentRequest;
import com.gahyeonbot.services.ai.agent.AgentResult;
import com.gahyeonbot.services.ai.agent.AgentRuntime;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider-independent conversation admission, usage ledger, and agent execution service.
 *
 * 비용 절감 및 보안 전략:
 * 1. OpenAI Moderation API: 프롬프트 인젝션 자동 차단 (최우선 방어선)
 * 2. 키워드 필터: 공백/특수문자 우회 방지 (보조 방어선)
 * 3. Rate Limiting: 사용자당 1시간 10회, 하루 30회 제한
 * 4. 봇 전체 제한: 하루 50회, 월 100회
 * 5. 중복 차단: 10초 내 재요청 차단
 * 6. DB 로깅: 모든 요청 기록 및 비용 추적
 *
 * @author Gahyeon Team
 * @version 2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationAdmissionService {

    private final OpenAiUsageRepository usageRepository;
    private final AppCredentialsConfig appCredentialsConfig;
    private final AgentRuntime agentRuntime;
    private final ConversationAdmissionPolicy admissionPolicy;

    private String apiKey;
    private String agentApiKey;
    private boolean isEnabled = false;
    private RestTemplate restTemplate;

    // 사용자별 Lock: 동일 사용자의 동시 요청 방지 (ShardManager 동시성 제어)
    private final Map<Long, Lock> userLocks = new ConcurrentHashMap<>();

    private static final int DUPLICATE_CHECK_SECONDS = 10;    // 중복 요청 차단 시간

    @PostConstruct
    public void initialize() {
        // AppCredentialsConfig에서 API 키 가져오기
        this.apiKey = appCredentialsConfig.getOpenaiApiKey();
        this.agentApiKey = appCredentialsConfig.getAgentApiKey();

        if (agentApiKey == null || agentApiKey.isBlank() || agentApiKey.startsWith("your_")) {
            log.warn("AgentRuntime API 키가 설정되지 않았습니다. AI 기능이 비활성화됩니다.");
            this.isEnabled = false;
            return;
        }

        try {
            // RestTemplate 초기화
            this.restTemplate = new RestTemplate();

            this.isEnabled = true;
            log.info("AI 게이트웨이가 활성화되었습니다. AgentRuntime + Rate Limiting + Moderation API 적용");
        } catch (Exception e) {
            log.error("OpenAI 초기화 실패. OpenAI 기능이 비활성화됩니다.", e);
            this.isEnabled = false;
        }
    }

    /**
     * 사용자 질문에 대해 AI 응답을 생성합니다.
     * 모든 Rate Limiting 및 보안 검사를 통과해야 합니다.
     *
     * 다중 인스턴스 대응:
     * - Interaction ID 기반 중복 방지 (여러 봇 인스턴스 병렬 실행 시)
     * - DB UNIQUE 제약조건으로 첫 번째 인스턴스만 처리
     *
     * ShardManager 동시성 제어:
     * - 동일 사용자의 요청은 Lock으로 순차 처리 (Race Condition 방지)
     * - 다른 사용자의 요청은 병렬 처리 (성능 유지)
     *
     * @param interactionId Discord Interaction ID (중복 방지용)
     * @param userId 사용자 ID
     * @param username 사용자 이름
     * @param toolScopeId optional numeric scope used by legacy tool adapters
     * @param userMessage 사용자의 질문 또는 메시지
     * @return AI의 응답 텍스트
     * @throws RateLimitException Rate Limit 초과 시
     * @throws AdversarialPromptException 적대적 프롬프트 감지 시
     */
    public String chat(String interactionId, Long userId, String username, Long toolScopeId, String userMessage) throws RateLimitException, AdversarialPromptException {
        return chatResult(interactionId, userId, username, toolScopeId, userMessage).content();
    }

    /**
     * Platform adapters use this result-preserving entry point so run identity,
     * tool usage, and duration are not lost at the Core boundary.
     */
    public AgentResult chatResult(
            String interactionId,
            Long userId,
            String username,
            Long toolScopeId,
            String userMessage) throws RateLimitException, AdversarialPromptException {
        // 사용자별 Lock 획득 (동일 사용자의 동시 요청 방지)
        Lock userLock = userLocks.computeIfAbsent(userId, k -> new ReentrantLock());
        userLock.lock();
        try {
            return chatInternal(interactionId, userId, username, toolScopeId, userMessage);
        } finally {
            userLock.unlock();
        }
    }

    /**
     * 내부 chat 메서드 (Lock으로 보호됨)
     */
    @Transactional
    private AgentResult chatInternal(String interactionId, Long userId, String username, Long toolScopeId, String userMessage) throws RateLimitException, AdversarialPromptException {
        if (!isEnabled) {
            throw new RateLimitException("OpenAI 서비스가 비활성화되어 있습니다.");
        }

        boolean moderationFlagged = false;
        if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("your_")) {
            try {
                moderationFlagged = checkModeration(userMessage);
            } catch (Exception e) {
                log.error("Moderation API 호출 실패 - 키워드 필터로 대체", e);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = LocalDateTime.now().minusSeconds(DUPLICATE_CHECK_SECONDS);
        LocalDateTime oneHourAgo = now.minusHours(1);
        LocalDateTime oneDayAgo = now.minusDays(1);
        LocalDateTime monthStart = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
        boolean duplicate = userMessage != null && !userMessage.isBlank()
                && !usageRepository.findDuplicatePrompt(userId, userMessage, since).isEmpty();
        long hourlyUsage = usageRepository.countByUserIdAndCreatedAtAfter(userId, oneHourAgo);
        long dailyUsage = usageRepository.countByUserIdAndCreatedAtAfter(userId, oneDayAgo);
        long totalDailyUsage = usageRepository.countByCreatedAtAfter(oneDayAgo);
        long monthlyUsage = usageRepository.countMonthlyUsage(monthStart);
        AdmissionDecision decision = admissionPolicy.decide(userMessage, new AdmissionFacts(
                moderationFlagged, duplicate, hourlyUsage, dailyUsage, totalDailyUsage, monthlyUsage));
        if (!decision.accepted()) {
            log.warn("대화 admission 거절 user={} reason={}", username, decision.reason());
            if (decision.reason() == AdmissionDecision.Reason.INVALID_INPUT) {
                throw new IllegalArgumentException(decision.message());
            }
            if (decision.reason() == AdmissionDecision.Reason.UNSAFE_INPUT) {
                logUsage(interactionId, userId, username, toolScopeId, userMessage, null, false,
                        decision.reason().name());
                throw new AdversarialPromptException(decision.message());
            }
            throw new RateLimitException(decision.message());
        }

        // 공통 에이전트 런타임 호출
        try {
            log.info("에이전트 요청 시작 - 사용자: {}, 메시지 길이: {} 문자", username, userMessage.length());
            AgentResult result = agentRuntime.execute(new AgentRequest(
                    interactionId,
                    "discord:text:" + userId,
                    AgentGateway.TEXT,
                    toolScopeId,
                    userId,
                    username,
                    userMessage,
                    8));
            String response = result.content();
            log.info("에이전트 응답 성공 - run={}, 사용자={}, 도구={}, {}ms",
                    result.runId(), username, result.tools(), result.duration().toMillis());

            // 10. 사용량 DB 로깅
            logUsage(interactionId, userId, username, toolScopeId, userMessage, response, true, null);

            return result;

        } catch (AgentApprovalRequiredException approvalRequired) {
            log.info("에이전트 승인 대기 - run={}, tool={}",
                    approvalRequired.getRunId(), approvalRequired.getToolName());
            throw approvalRequired;
        } catch (Exception e) {
            log.error("OpenAI API 호출 실패 - 사용자: {}, 메시지: {}", username, userMessage, e);
            logUsage(interactionId, userId, username, toolScopeId, userMessage, null, false, e.getMessage());
            throw new ChatProcessingException(ChatProcessingException.ErrorType.OPENAI_API_FAILURE,
                    "AI 응답을 받지 못했습니다. 잠시 후 다시 시도해주세요.", e);
        }
    }

    /**
     * 사용량을 DB에 로깅합니다.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException 이미 처리된 interaction_id인 경우
     */
    private void logUsage(String interactionId, Long userId, String username, Long toolScopeId, String prompt, String response, boolean success, String errorMessage) {
        try {
            OpenAiUsage usage = OpenAiUsage.builder()
                    .interactionId(interactionId)
                    .userId(userId)
                    .username(username)
                    .guildId(toolScopeId)
                    .prompt(prompt)
                    .response(response)
                    .model("agent-runtime")
                    .success(success)
                    .errorMessage(errorMessage)
                    .createdAt(LocalDateTime.now())
                    .build();

            usageRepository.save(usage);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // UNIQUE 제약조건 위반: 다른 인스턴스가 이미 처리 중
            log.warn("중복 Interaction ID 감지 - 다른 인스턴스가 처리 중: {}", interactionId);
            throw e;
        } catch (Exception e) {
            log.error("사용량 로깅 실패", e);
        }
    }

    /**
     * 서비스 활성화 상태를 확인합니다.
     */
    public boolean isEnabled() {
        return isEnabled;
    }

    /**
     * OpenAI Moderation API를 사용하여 프롬프트의 적절성을 검사합니다.
     *
     * @param message 검사할 메시지
     * @return true: 부적절한 콘텐츠 감지됨 (차단해야 함), false: 안전함
     */
    private boolean checkModeration(String message) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            Map<String, String> body = Map.of("input", message);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    "https://api.openai.com/v1/moderations",
                    HttpMethod.POST,
                    request,
                    new ParameterizedTypeReference<>() {
                    }
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                Map<String, Object> responseBody = response.getBody();
                if (responseBody != null) {
                    Object resultsObj = responseBody.get("results");
                    if (resultsObj instanceof List<?> results && !results.isEmpty()) {
                        Object first = results.get(0);
                        if (first instanceof Map<?, ?> firstResult) {
                            Object flaggedObj = firstResult.get("flagged");
                            if (flaggedObj instanceof Boolean flagged && flagged) {
                                log.warn("Moderation API 차단: 부적절한 콘텐츠 감지");
                                return true;
                            }
                        }
                    }
                }
            }
            return false;

        } catch (Exception e) {
            log.error("Moderation API 호출 실패 - 키워드 필터로 대체", e);
            // API 호출 실패 시 false 반환 (키워드 필터가 대신 처리)
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
     * OpenAI 처리 중 발생한 일반 오류
     */
    public static class ChatProcessingException extends RuntimeException {
        public enum ErrorType {
            OPENAI_API_FAILURE,
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
