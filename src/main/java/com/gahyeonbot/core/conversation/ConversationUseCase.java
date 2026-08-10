package com.gahyeonbot.core.conversation;

public interface ConversationUseCase {
    ConversationResponse converse(ConversationRequest request);
}
