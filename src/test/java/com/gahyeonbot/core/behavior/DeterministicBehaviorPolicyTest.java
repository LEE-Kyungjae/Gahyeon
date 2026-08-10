package com.gahyeonbot.core.behavior;

import com.gahyeonbot.core.world.WorldActivity;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldStateSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicBehaviorPolicyTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void selectsSleepAtNightAndKeepsSleepingStateStable() {
        Instant now = Instant.parse("2026-08-09T18:30:00Z"); // 03:30 KST
        var policy = new DeterministicBehaviorPolicy(Clock.fixed(now, ZoneOffset.UTC), SEOUL);
        var state = WorldStateSnapshot.initial(new WorldId("gahyeon-home"), now.minusSeconds(600));

        var decision = policy.decide(state, GahyeonHomeWorld.definition()).orElseThrow();

        assertThat(decision.activity()).isEqualTo(WorldActivity.SLEEP);
        assertThat(decision.target().id()).isEqualTo("bed");
        assertThat(policy.decide(withActivity(state, WorldActivity.SLEEP, now), GahyeonHomeWorld.definition()))
                .isEmpty();
    }

    @Test
    void doesNotInterruptAttentionAndCyclesDeterministicallyAfterIdleWindow() {
        Instant now = Instant.parse("2026-08-10T05:00:00Z"); // 14:00 KST
        var policy = new DeterministicBehaviorPolicy(Clock.fixed(now, ZoneOffset.UTC), SEOUL);
        var initial = WorldStateSnapshot.initial(new WorldId("gahyeon-home"), now.minusSeconds(120));

        assertThat(policy.decide(initial, GahyeonHomeWorld.definition()))
                .get()
                .extracting(BehaviorDecision::activity)
                .isEqualTo(WorldActivity.WORK);
        assertThat(policy.decide(
                withActivity(initial, WorldActivity.ATTENTION, now.minus(Duration.ofHours(1))),
                GahyeonHomeWorld.definition())).isEmpty();
    }

    private WorldStateSnapshot withActivity(
            WorldStateSnapshot state,
            WorldActivity activity,
            Instant startedAt) {
        return new WorldStateSnapshot(
                state.worldId(), state.revision(), state.currentRoom(), state.position(),
                activity, startedAt, state.outfit(), state.worldTime(), state.emotion(),
                state.interactionTarget(), state.updatedAt());
    }
}
