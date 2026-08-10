package com.gahyeonbot.core.conversation;

import com.gahyeonbot.core.session.ConversationSession;

public record ConversationRequest(
        String requestId,
        ConversationSession session,
        String displayName,
        String message
) {
    public ConversationRequest {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId가 필요합니다.");
        }
        requestId = requestId.trim();
        if (requestId.length() > 120) {
            throw new IllegalArgumentException("requestId는 120자를 넘을 수 없습니다.");
        }
        if (session == null) throw new IllegalArgumentException("session이 필요합니다.");
        displayName = displayName == null || displayName.isBlank() ? "unknown" : displayName.trim();
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message가 필요합니다.");
        message = message.trim();
    }
}
