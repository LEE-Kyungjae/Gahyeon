package com.gahyeonbot.core.behavior;

import java.util.Map;

public record WorldDefinition(String worldId, Map<String, InteractionPoint> interactionPoints) {
    public WorldDefinition {
        if (worldId == null || worldId.isBlank()) throw new IllegalArgumentException("worldId가 필요합니다.");
        interactionPoints = interactionPoints == null ? Map.of() : Map.copyOf(interactionPoints);
    }

    public InteractionPoint requirePoint(String id) {
        InteractionPoint point = interactionPoints.get(id);
        if (point == null) throw new IllegalArgumentException("등록되지 않은 interaction point: " + id);
        return point;
    }
}
