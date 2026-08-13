package com.gahyeonbot.services.ai.agent;

import com.gahyeonbot.core.identity.ActorId;

public record AgentRunRequest(
        String requestId,
        String sessionKey,
        AgentModality modality,
        Long toolScopeId,
        ActorId actorId,
        String actorDisplayName,
        String input,
        int maxSteps
) {
    public AgentRunRequest {
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId가 필요합니다.");
        if (sessionKey == null || sessionKey.isBlank()) throw new IllegalArgumentException("sessionKey가 필요합니다.");
        if (modality == null) throw new IllegalArgumentException("modality가 필요합니다.");
        if (actorId == null) throw new IllegalArgumentException("actorId가 필요합니다.");
        if (input == null || input.isBlank()) throw new IllegalArgumentException("input이 필요합니다.");
        actorDisplayName = actorDisplayName == null || actorDisplayName.isBlank()
                ? "unknown" : actorDisplayName;
        input = input.trim();
        if (maxSteps < 1 || maxSteps > 32) throw new IllegalArgumentException("maxSteps는 1~32여야 합니다.");
    }
}
