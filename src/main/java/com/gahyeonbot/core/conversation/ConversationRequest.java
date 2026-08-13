package com.gahyeonbot.core.conversation;

import com.gahyeonbot.core.session.ConversationSession;

public record ConversationRequest(
        String requestId,
        ConversationSession session,
        String displayName,
        String message
) {
    public static final int MAXIMUM_REQUEST_ID_CHARACTERS = 120;
    public static final int MAXIMUM_DISPLAY_NAME_CHARACTERS = 100;
    public static final int MAXIMUM_MESSAGE_CHARACTERS = 16_384;

    public ConversationRequest {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId가 필요합니다.");
        }
        requestId = requestId.trim();
        if (requestId.length() > MAXIMUM_REQUEST_ID_CHARACTERS) {
            throw new IllegalArgumentException("requestId는 120자를 넘을 수 없습니다.");
        }
        if (session == null) throw new IllegalArgumentException("session이 필요합니다.");
        displayName = displayName == null || displayName.isBlank() ? "unknown" : displayName.trim();
        if (displayName.length() > MAXIMUM_DISPLAY_NAME_CHARACTERS) {
            throw new IllegalArgumentException("displayName은 100자를 넘을 수 없습니다.");
        }
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message가 필요합니다.");
        message = message.trim();
        if (message.length() > MAXIMUM_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException("message는 16384자를 넘을 수 없습니다.");
        }
    }
}
