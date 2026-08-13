package com.gahyeonbot.services.ai.agent;

import com.gahyeonbot.core.identity.ActorId;

public record AgentRequest(
        String requestId,
        String sessionKey,
        AgentModality modality,
        Long toolScopeId,
        ActorId actorId,
        String actorDisplayName,
        String message,
        int maxSteps
) {
    public AgentRequest {
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId가 필요합니다.");
        if (sessionKey == null || sessionKey.isBlank()) throw new IllegalArgumentException("sessionKey가 필요합니다.");
        if (modality == null) throw new IllegalArgumentException("modality가 필요합니다.");
        if (actorId == null) throw new IllegalArgumentException("actorId가 필요합니다.");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message가 필요합니다.");
        actorDisplayName = actorDisplayName == null || actorDisplayName.isBlank()
                ? "unknown" : actorDisplayName;
        message = message.trim();
        if (maxSteps < 1 || maxSteps > 32) throw new IllegalArgumentException("maxSteps는 1~32여야 합니다.");
    }

    public AgentRunRequest toRunRequest() {
        return new AgentRunRequest(
                requestId, sessionKey, modality, toolScopeId, actorId, actorDisplayName, message, maxSteps);
    }
}
