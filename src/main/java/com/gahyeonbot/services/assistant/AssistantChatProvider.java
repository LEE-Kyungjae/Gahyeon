package com.gahyeonbot.services.assistant;

import com.gahyeonbot.core.conversation.ConversationRequest;

public interface AssistantChatProvider {
    boolean isReady();
    String chat(ConversationRequest request);
}
