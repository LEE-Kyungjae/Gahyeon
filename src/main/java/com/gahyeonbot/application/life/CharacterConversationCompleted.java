package com.gahyeonbot.application.life;

import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;

public record CharacterConversationCompleted(ConversationRequest request, ConversationResponse response) {
    public CharacterConversationCompleted {
        if (request == null || response == null) throw new IllegalArgumentException("conversation is required");
    }
}
