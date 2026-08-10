package com.gahyeonbot.core.world;

import com.gahyeonbot.core.emotion.EmotionState;
import java.time.Instant;

public record WorldStateSnapshot(
        WorldId worldId,
        long revision,
        String currentRoom,
        WorldPosition position,
        WorldActivity activity,
        Instant activityStartedAt,
        String outfit,
        Instant worldTime,
        String emotion,
        double emotionIntensity,
        String interactionTarget,
        Instant updatedAt
) {
    public WorldStateSnapshot {
        if (worldId == null) throw new IllegalArgumentException("worldId가 필요합니다.");
        if (revision < 0) throw new IllegalArgumentException("revision은 0 이상이어야 합니다.");
        if (currentRoom == null || currentRoom.isBlank()) throw new IllegalArgumentException("currentRoom이 필요합니다.");
        if (position == null) throw new IllegalArgumentException("position이 필요합니다.");
        if (activity == null) throw new IllegalArgumentException("activity가 필요합니다.");
        if (activityStartedAt == null) throw new IllegalArgumentException("activityStartedAt이 필요합니다.");
        if (outfit == null || outfit.isBlank()) throw new IllegalArgumentException("outfit이 필요합니다.");
        if (worldTime == null) throw new IllegalArgumentException("worldTime이 필요합니다.");
        if (emotion == null || emotion.isBlank()) throw new IllegalArgumentException("emotion이 필요합니다.");
        new EmotionState(emotion, emotionIntensity);
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt이 필요합니다.");
    }

    public static WorldStateSnapshot initial(WorldId worldId, Instant now) {
        return new WorldStateSnapshot(
                worldId,
                0,
                "bedroom",
                WorldPosition.origin(),
                WorldActivity.IDLE,
                now,
                "default",
                now,
                "neutral",
                0,
                null,
                now);
    }

    public EmotionState emotionState() {
        return new EmotionState(emotion, emotionIntensity);
    }
}
