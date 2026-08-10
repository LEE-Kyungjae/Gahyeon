package com.gahyeonbot.core.behavior;

import com.gahyeonbot.core.world.WorldActivity;

public record BehaviorDecision(WorldActivity activity, InteractionPoint target) {
    public BehaviorDecision {
        if (activity == null) throw new IllegalArgumentException("activity가 필요합니다.");
        if (target == null) throw new IllegalArgumentException("target이 필요합니다.");
        if (!target.supports(activity)) {
            throw new IllegalArgumentException(target.id() + "에서 지원하지 않는 activity: " + activity);
        }
    }
}
