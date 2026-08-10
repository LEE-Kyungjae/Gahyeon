package com.gahyeonbot.application.conversation;

import com.gahyeonbot.application.event.GahyeonEventPublisher;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.core.conversation.ConversationUseCase;
import com.gahyeonbot.core.event.GahyeonEventDraft;
import com.gahyeonbot.core.event.GahyeonEventTypes;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class GahyeonConversationService implements ConversationUseCase {
    private final ConversationAgentPort agentPort;
    private final GahyeonEventPublisher events;

    public GahyeonConversationService(
            ConversationAgentPort agentPort,
            GahyeonEventPublisher events) {
        this.agentPort = agentPort;
        this.events = events;
    }

    @Override
    public ConversationResponse converse(ConversationRequest request) {
        events.publish(new GahyeonEventDraft(
                GahyeonEventTypes.CONVERSATION_STARTED,
                request.session().id(),
                request.requestId(),
                Map.of(
                        "source", request.session().source().name().toLowerCase(),
                        "modality", request.session().modality().name().toLowerCase(),
                        "actorId", request.session().actorId().value())));
        try {
            ConversationResponse response = agentPort.execute(request);
            Map<String, Object> payload = new LinkedHashMap<>();
            if (response.runId() != null) payload.put("runId", response.runId());
            payload.put("content", response.content());
            payload.put("tools", response.tools());
            payload.put("durationMillis", response.duration().toMillis());
            events.publish(new GahyeonEventDraft(
                    GahyeonEventTypes.CONVERSATION_COMPLETED,
                    request.session().id(),
                    request.requestId(),
                    payload));
            return response;
        } catch (RuntimeException failure) {
            events.publish(new GahyeonEventDraft(
                    GahyeonEventTypes.CONVERSATION_FAILED,
                    request.session().id(),
                    request.requestId(),
                    Map.of(
                            "errorType", failure.getClass().getSimpleName(),
                            "message", failure.getMessage() == null ? "" : failure.getMessage())));
            throw failure;
        }
    }
}
