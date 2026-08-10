package com.gahyeonbot.adapters.agent;

import com.gahyeonbot.application.conversation.ConversationAgentPort;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationRejectedException;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.services.ai.ConversationAdmissionService;
import com.gahyeonbot.services.ai.agent.AgentGateway;
import com.gahyeonbot.services.ai.agent.AgentResult;
import org.springframework.stereotype.Component;

/**
 * Bridges the Core contract to admission-controlled agent execution.
 */
@Component
public class AdmissionControlledConversationAdapter implements ConversationAgentPort {
    private final ConversationAdmissionService admission;

    public AdmissionControlledConversationAdapter(ConversationAdmissionService admission) {
        this.admission = admission;
    }

    @Override
    public ConversationResponse execute(ConversationRequest request) {
        Long toolScopeId = toolScopeId(request);
        AgentResult result;
        try {
            result = admission.chatResult(
                    request.requestId(),
                    request.session().id().value(),
                    AgentGateway.valueOf(request.session().modality().name()),
                    request.session().actorId().value(),
                    request.displayName(),
                    toolScopeId,
                    request.message());
        } catch (ConversationAdmissionService.RateLimitException exception) {
            throw new ConversationRejectedException(
                    ConversationRejectedException.Reason.RATE_LIMITED,
                    exception.getMessage(), exception);
        } catch (ConversationAdmissionService.AdversarialPromptException exception) {
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

    private static Long toolScopeId(ConversationRequest request) {
        String value = request.session().clientContext().get("agent.toolScopeId");
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("agent.toolScopeId가 올바르지 않습니다.", exception);
        }
    }
}
