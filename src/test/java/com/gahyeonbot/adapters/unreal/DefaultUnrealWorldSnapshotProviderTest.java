package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.world.WorldActivity;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldPosition;
import com.gahyeonbot.core.world.WorldStateSnapshot;
import com.gahyeonbot.core.world.WorldStateUseCase;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultUnrealWorldSnapshotProviderTest {
    @Test
    void mapsTheCompleteAuthoritativeWorldStateWithoutNullPayloadValues() {
        Instant updatedAt = Instant.parse("2026-08-11T03:00:00Z");
        WorldStateUseCase worlds = mock(WorldStateUseCase.class);
        when(worlds.current(new WorldId("gahyeon-home"))).thenReturn(new WorldStateSnapshot(
                new WorldId("gahyeon-home"),
                7,
                "workspace",
                new WorldPosition(1.0, 2.0, 3.0),
                WorldActivity.WORK,
                updatedAt.minusSeconds(30),
                "casual",
                updatedAt,
                "focused",
                0.65,
                null,
                updatedAt));
        var provider = new DefaultUnrealWorldSnapshotProvider(
                worlds, Clock.fixed(updatedAt.plusSeconds(1), ZoneOffset.UTC));

        var payload = provider.snapshot("gahyeon-home");

        assertThat(payload)
                .containsEntry("worldId", "gahyeon-home")
                .containsEntry("revision", 7L)
                .containsEntry("currentRoom", "workspace")
                .containsEntry("activity", "work")
                .containsEntry("outfit", "casual")
                .containsEntry("capturedAt", updatedAt.plusSeconds(1))
                .doesNotContainKey("interactionTarget");
        assertThat(payload.get("position")).isEqualTo(java.util.Map.of(
                "x", 1.0, "y", 2.0, "z", 3.0));
        assertThat(payload.get("emotion")).isEqualTo(java.util.Map.of(
                "name", "focused", "intensity", 0.65));
    }
}
