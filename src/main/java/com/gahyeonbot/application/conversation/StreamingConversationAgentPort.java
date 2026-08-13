package com.gahyeonbot.application.conversation;

import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;

/** Optional capability; synchronous agent ports continue to use ConversationAgentPort. */
public interface StreamingConversationAgentPort extends ConversationAgentPort {
    ConversationResponse executeStreaming(
            ConversationRequest request,
            ConversationStreamObserver observer);
}
