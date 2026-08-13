package com.gahyeonbot.adapters.agent;

import com.gahyeonbot.application.conversation.ConversationStreamObserver;
import com.gahyeonbot.application.conversation.StreamingConversationAgentPort;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationRejectedException;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.services.ai.ConversationAdmissionService;
import com.gahyeonbot.services.ai.agent.AgentModality;
import com.gahyeonbot.services.ai.agent.AgentResult;
import org.springframework.stereotype.Component;

/**
 * Bridges the Core contract to admission-controlled agent execution.
 */
@Component
public class AdmissionControlledConversationAdapter implements StreamingConversationAgentPort {
    private final ConversationAdmissionService admission;

    public AdmissionControlledConversationAdapter(ConversationAdmissionService admission) {
        this.admission = admission;
    }

    @Override
    public ConversationResponse execute(ConversationRequest request) {
        return execute(request, null);
    }

    @Override
    public ConversationResponse executeStreaming(
            ConversationRequest request,
            ConversationStreamObserver observer) {
        if (observer == null) throw new IllegalArgumentException("stream observer가 필요합니다.");
        return execute(request, observer);
    }

    private ConversationResponse execute(
            ConversationRequest request,
            ConversationStreamObserver observer) {
        Long toolScopeId = toolScopeId(request);
        AgentResult result;
        try {
            if (observer == null) {
                result = admission.chatResult(
                        request.requestId(), request.session().id().value(),
                        AgentModality.valueOf(request.session().modality().name()),
                        request.session().actorId(), request.displayName(),
                        toolScopeId, request.message());
            } else {
                result = admission.chatResultStreaming(
                        request.requestId(), request.session().id().value(),
                        AgentModality.valueOf(request.session().modality().name()),
                        request.session().actorId(), request.displayName(),
                        toolScopeId, request.message(), new com.gahyeonbot.services.ai.agent.AgentStreamObserver() {
                            @Override
                            public void onTextDelta(String delta) {
                                observer.onTextDelta(delta);
                            }

                            @Override
                            public boolean isCancelled() {
                                return observer.isCancelled();
                            }
                        });
            }
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
