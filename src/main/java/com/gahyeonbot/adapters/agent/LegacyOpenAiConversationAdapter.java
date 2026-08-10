package com.gahyeonbot.adapters.agent;

import com.gahyeonbot.application.conversation.ConversationAgentPort;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationRejectedException;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.services.ai.OpenAiService;
import com.gahyeonbot.services.ai.agent.AgentResult;
import org.springframework.stereotype.Component;

/**
 * Bridges the new Core contract to the existing admission-controlled conversation path.
 * Discord-specific compatibility is deliberately confined to this adapter.
 */
@Component
public class LegacyOpenAiConversationAdapter implements ConversationAgentPort {
    private final OpenAiService openAiService;

    public LegacyOpenAiConversationAdapter(OpenAiService openAiService) {
        this.openAiService = openAiService;
    }

    @Override
    public ConversationResponse execute(ConversationRequest request) {
        Long legacyGuildId = legacyGuildId(request);
        AgentResult result;
        try {
            result = openAiService.chatResult(
                    request.requestId(),
                    request.session().actorId().value(),
                    request.displayName(),
                    legacyGuildId,
                    request.message());
        } catch (OpenAiService.RateLimitException exception) {
            throw new ConversationRejectedException(
                    ConversationRejectedException.Reason.RATE_LIMITED,
                    exception.getMessage(), exception);
        } catch (OpenAiService.AdversarialPromptException exception) {
            throw new ConversationRejectedException(
                    ConversationRejectedException.Reason.UNSAFE_INPUT,
                    exception.getMessage(), exception);
        }
        return new ConversationResponse(
                result.runId(),
                result.content(),
                result.tools(),
                result.duration());
    }

    private static Long legacyGuildId(ConversationRequest request) {
        String value = request.session().clientContext().get("discord.guildId");
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("discord.guildId가 올바르지 않습니다.", exception);
        }
    }
}
