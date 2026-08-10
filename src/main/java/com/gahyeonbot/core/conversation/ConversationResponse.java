package com.gahyeonbot.core.conversation;

import java.time.Duration;
import java.util.List;

public record ConversationResponse(
        String runId,
        String content,
        List<String> tools,
        Duration duration
) {
    public ConversationResponse {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
