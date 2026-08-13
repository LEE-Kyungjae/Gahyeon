package com.gahyeonbot.adapters.unreal;

import java.util.Map;

/** Supplies the authoritative world state used during the Unreal hello handshake. */
@FunctionalInterface
public interface UnrealWorldSnapshotProvider {
    Map<String, Object> snapshot(String worldId);

    static UnrealWorldSnapshotProvider unavailable() {
        return worldId -> Map.of();
    }
}
