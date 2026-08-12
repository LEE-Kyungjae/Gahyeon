package com.gahyeonbot.services.ai;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.memory.MemoryCompactionPort;
import com.gahyeonbot.core.memory.MemoryMessage;
import com.gahyeonbot.core.memory.MemoryRole;
import com.gahyeonbot.core.memory.MemorySnapshot;
import com.gahyeonbot.core.memory.MemoryUseCase;
import com.gahyeonbot.entity.ConversationHistory;
import com.gahyeonbot.repository.ConversationHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 대화 히스토리 관리 서비스.
 * 최근 5건은 전체 내용을 유지하고, 이전 대화는 요약하여 컨텍스트로 활용합니다.
 *
 * @author GahyeonBot Team
 * @version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationHistoryService implements MemoryUseCase {

    private static final int RECENT_CONVERSATION_COUNT = 5;
    private static final int MAX_SUMMARY_CONTEXT_COUNT = 10;

    private final ConversationHistoryRepository repository;
    private final MemoryCompactionPort memoryCompaction;

    /**
     * 대화를 저장합니다.
     *
     * @param actorId Gahyeon 내부 Actor ID
     * @param userMessage 사용자 메시지
     * @param aiResponse AI 응답
     */
    @Transactional
    public void saveConversation(ActorId actorId, String userMessage, String aiResponse) {
        persistConversation(actorId, userMessage, aiResponse);
    }

    private void persistConversation(ActorId actorId, String userMessage, String aiResponse) {
        ConversationHistory history = ConversationHistory.builder()
                .actorId(actorId.value())
                .userMessage(userMessage)
                .aiResponse(aiResponse)
                .createdAt(LocalDateTime.now())
                .build();

        repository.save(history);
        log.debug("대화 저장 완료 - actor: {}", actorId.value());

        // 실제 provider I/O는 트랜잭션 커밋 후 전용 bounded worker에서 수행됩니다.
        try {
            memoryCompaction.requestCompaction(actorId);
        } catch (RuntimeException schedulingFailure) {
            // Compaction is optional maintenance. Its admission path must never roll back the
            // durable conversation that has already been accepted by this transaction.
            log.warn("메모리 압축 예약 실패 - actor: {}; 다음 대화에서 재시도합니다.",
                    actorId.value(), schedulingFailure);
        }
    }

    /**
     * 대화 컨텍스트를 빌드합니다.
     * 형식: [이전 대화 요약] + [최근 5건 전체 대화]
     *
     * @param actorId Gahyeon 내부 Actor ID
     * @return 컨텍스트 문자열
     */
    @Transactional(readOnly = true)
    public String buildContext(ActorId actorId) {
        StringBuilder context = new StringBuilder();

        // 1. 요약된 이전 대화 조회
        List<ConversationHistory> summaries = repository.findLatestSummary(
                actorId.value(), PageRequest.of(0, MAX_SUMMARY_CONTEXT_COUNT));

        if (!summaries.isEmpty()) {
            context.append("[이전 대화 요약]\n");
            // 오래된 순서로 정렬
            List<ConversationHistory> orderedSummaries = new ArrayList<>(summaries);
            Collections.reverse(orderedSummaries);
            for (ConversationHistory summary : orderedSummaries) {
                if (summary.getSummary() != null) {
                    context.append("- ").append(summary.getSummary()).append("\n");
                }
            }
            context.append("\n");
        }

        // 2. 최근 5건 전체 대화 조회
        List<ConversationHistory> recentConversations = repository.findRecentByActorId(
                actorId.value(), PageRequest.of(0, RECENT_CONVERSATION_COUNT));

        if (!recentConversations.isEmpty()) {
            context.append("[최근 대화]\n");
            // 오래된 순서로 정렬 (대화 순서대로)
            List<ConversationHistory> orderedRecent = new ArrayList<>(recentConversations);
            Collections.reverse(orderedRecent);
            for (ConversationHistory conv : orderedRecent) {
                context.append("사용자: ").append(truncate(conv.getUserMessage(), 100)).append("\n");
                context.append("가현이: ").append(truncate(conv.getAiResponse(), 150)).append("\n\n");
            }
        }

        String result = context.toString().trim();
        if (!result.isEmpty()) {
            log.debug("컨텍스트 빌드 완료 - actor: {}, 길이: {}자", actorId.value(), result.length());
        }
        return result;
    }

    /**
     * 에이전트 호출에 사용할 역할 기반 대화 메시지와 장기 요약을 반환합니다.
     * 이전 대화를 하나의 사용자 문자열로 합치지 않아 user/assistant 경계가 보존됩니다.
     */
    @Transactional(readOnly = true)
    public MemorySnapshot buildAgentContext(ActorId actorId) {
        List<ConversationHistory> summaries = repository.findLatestSummary(
                actorId.value(), PageRequest.of(0, MAX_SUMMARY_CONTEXT_COUNT));
        List<ConversationHistory> orderedSummaries = new ArrayList<>(summaries);
        Collections.reverse(orderedSummaries);
        String summary = orderedSummaries.stream()
                .map(ConversationHistory::getSummary)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.joining("\n- ", "- ", ""));

        List<ConversationHistory> recent = repository.findRecentByActorId(
                actorId.value(), PageRequest.of(0, RECENT_CONVERSATION_COUNT));
        List<ConversationHistory> orderedRecent = new ArrayList<>(recent);
        Collections.reverse(orderedRecent);
        List<MemoryMessage> messages = new ArrayList<>(orderedRecent.size() * 2);
        for (ConversationHistory conversation : orderedRecent) {
            if (conversation.getUserMessage() != null && !conversation.getUserMessage().isBlank()) {
                messages.add(new MemoryMessage(MemoryRole.USER, conversation.getUserMessage()));
            }
            if (conversation.getAiResponse() != null && !conversation.getAiResponse().isBlank()) {
                messages.add(new MemoryMessage(MemoryRole.ASSISTANT, conversation.getAiResponse()));
            }
        }
        return new MemorySnapshot(summary, messages);
    }

    @Override
    public MemorySnapshot recall(ActorId actorId) {
        return buildAgentContext(actorId);
    }

    @Override
    @Transactional
    public void remember(ActorId actorId, String userMessage, String assistantResponse) {
        persistConversation(actorId, userMessage, assistantResponse);
    }

    @Override
    public void clear(ActorId actorId) {
        clearHistory(actorId);
    }

    /**
     * 사용자의 대화 히스토리를 초기화합니다.
     *
     * @param actorId Gahyeon 내부 Actor ID
     */
    @Transactional
    public void clearHistory(ActorId actorId) {
        List<ConversationHistory> histories = repository.findRecentByActorId(
                actorId.value(), PageRequest.of(0, 1000));
        repository.deleteAll(histories);
        log.info("대화 히스토리 초기화 - actor: {}", actorId.value());
    }

    /**
     * 문자열 자르기
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }
}
