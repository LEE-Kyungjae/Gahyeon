package com.gahyeonbot.application.conversation;

import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.core.conversation.ConversationUseCase;
import org.springframework.stereotype.Service;

@Service
public class GahyeonConversationService implements ConversationUseCase {
    private final ConversationAgentPort agentPort;

    public GahyeonConversationService(ConversationAgentPort agentPort) {
        this.agentPort = agentPort;
    }

    @Override
    public ConversationResponse converse(ConversationRequest request) {
        return agentPort.execute(request);
    }
}
