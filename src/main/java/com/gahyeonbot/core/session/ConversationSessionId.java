package com.gahyeonbot.core.session;

public record ConversationSessionId(String value) {
    public ConversationSessionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("sessionId가 필요합니다.");
        }
        value = value.trim();
        if (value.length() > 200) {
            throw new IllegalArgumentException("sessionId는 200자를 넘을 수 없습니다.");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
