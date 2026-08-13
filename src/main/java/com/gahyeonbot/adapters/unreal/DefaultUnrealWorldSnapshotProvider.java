package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldStateSnapshot;
import com.gahyeonbot.core.world.WorldStateUseCase;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class DefaultUnrealWorldSnapshotProvider implements UnrealWorldSnapshotProvider {
    private final WorldStateUseCase worlds;
    private final Clock clock;

    public DefaultUnrealWorldSnapshotProvider(WorldStateUseCase worlds, Clock clock) {
        if (worlds == null) throw new IllegalArgumentException("worlds is required");
        if (clock == null) throw new IllegalArgumentException("clock is required");
        this.worlds = worlds;
        this.clock = clock;
    }

    @Override
    public Map<String, Object> snapshot(String worldId) {
        WorldStateSnapshot state = worlds.current(new WorldId(worldId));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("worldId", state.worldId().value());
        payload.put("revision", state.revision());
        payload.put("currentRoom", state.currentRoom());
        payload.put("position", Map.of(
                "x", state.position().x(),
                "y", state.position().y(),
                "z", state.position().z()));
        payload.put("activity", state.activity().name().toLowerCase(Locale.ROOT));
        payload.put("activityStartedAt", state.activityStartedAt());
        payload.put("outfit", state.outfit());
        payload.put("worldTime", state.worldTime());
        payload.put("emotion", Map.of(
                "name", state.emotion(),
                "intensity", state.emotionIntensity()));
        if (state.interactionTarget() != null) {
            payload.put("interactionTarget", state.interactionTarget());
        }
        payload.put("updatedAt", state.updatedAt());
        payload.put("capturedAt", clock.instant());
        return payload;
    }
}
