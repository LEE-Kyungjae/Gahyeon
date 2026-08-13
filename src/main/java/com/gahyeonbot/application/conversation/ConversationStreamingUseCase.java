package com.gahyeonbot.application.conversation;

import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;

public interface ConversationStreamingUseCase {
    ConversationResponse converseStreaming(
            ConversationRequest request,
            ConversationStreamObserver observer);
}
