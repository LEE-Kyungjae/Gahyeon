package com.gahyeonbot.services.assistant;

import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Voice readiness and compatibility wrapper around the shared Conversation Core.
 *
 * 이름은 기존 설정과 호환하기 위해 유지하지만, 대화 상태와 도구 실행은
 * 텍스트 명령과 동일한 AgentRuntime이 담당한다.
 */
@Service
@RequiredArgsConstructor
public class OpenRouterAssistantProvider implements AssistantChatProvider {
    private final AssistantProperties properties;
    private final ConversationUseCase conversation;

    @Override
    public boolean isReady() {
        var p = properties.getOpenrouter();
        return properties.isEnabled() && p.isEnabled()
                && hasText(p.getApiKey()) && hasText(p.getModel());
    }

    @Override
    public String chat(ConversationRequest request) {
        if (!isReady()) {
            throw new IllegalStateException("OpenRouter 에이전트가 설정되지 않았습니다.");
        }
        return conversation.converse(request).content();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
