package com.gahyeonbot.application.conversation;

import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;

/** Outbound port from the platform-neutral conversation application service. */
public interface ConversationAgentPort {
    ConversationResponse execute(ConversationRequest request);
}
