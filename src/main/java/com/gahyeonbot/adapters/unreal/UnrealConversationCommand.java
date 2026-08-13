package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.session.ConversationModality;

public record UnrealConversationCommand(
        String requestId,
        String sessionId,
        String installationId,
        String displayName,
        ConversationModality modality,
        long generation,
        String text
) {
    public UnrealConversationCommand {
        requireText(requestId, "requestId");
        requireText(sessionId, "sessionId");
        requireText(installationId, "installationId");
        if (modality == null) throw new IllegalArgumentException("modality is required");
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        requireText(text, "text");
        displayName = displayName == null || displayName.isBlank() ? "Gahyeon user" : displayName.trim();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
