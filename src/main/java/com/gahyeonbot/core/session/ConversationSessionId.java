package com.gahyeonbot.core.session;

import java.util.Locale;

public record ConversationSessionId(String value) {
    public static final int MAXIMUM_INTERNAL_ID_CHARACTERS = 200;
    public static final int MAXIMUM_EXTERNAL_ID_CHARACTERS = 180;

    public ConversationSessionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("sessionId가 필요합니다.");
        }
        value = value.trim();
        if (value.length() > MAXIMUM_INTERNAL_ID_CHARACTERS) {
            throw new IllegalArgumentException("sessionId는 200자를 넘을 수 없습니다.");
        }
    }

    /**
     * Converts a client-local session identifier into a platform-isolated internal identifier.
     * Reapplying the same source is intentionally idempotent for reconnect protocols that retain
     * the server-issued identifier.
     */
    public static ConversationSessionId fromExternal(ClientSource source, String externalId) {
        if (source == null) throw new IllegalArgumentException("source가 필요합니다.");
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("external sessionId가 필요합니다.");
        }
        String trimmed = externalId.trim();
        String prefix = source.name().toLowerCase(Locale.ROOT) + ":";
        if (!trimmed.startsWith(prefix) && trimmed.length() > MAXIMUM_EXTERNAL_ID_CHARACTERS) {
            throw new IllegalArgumentException("external sessionId는 180자를 넘을 수 없습니다.");
        }
        return new ConversationSessionId(trimmed.startsWith(prefix) ? trimmed : prefix + trimmed);
    }

    @Override
    public String toString() {
        return value;
    }
}
