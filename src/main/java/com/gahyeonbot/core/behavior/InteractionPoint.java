package com.gahyeonbot.core.behavior;

import com.gahyeonbot.core.world.WorldActivity;
import com.gahyeonbot.core.world.WorldPosition;

import java.util.Set;

public record InteractionPoint(
        String id,
        String room,
        WorldPosition position,
        Set<WorldActivity> activities
) {
    public InteractionPoint {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("interaction point ID가 필요합니다.");
        if (room == null || room.isBlank()) throw new IllegalArgumentException("room이 필요합니다.");
        if (position == null) throw new IllegalArgumentException("position이 필요합니다.");
        activities = activities == null ? Set.of() : Set.copyOf(activities);
        if (activities.isEmpty()) throw new IllegalArgumentException("지원 activity가 필요합니다.");
    }

    public boolean supports(WorldActivity activity) {
        return activities.contains(activity);
    }
}
