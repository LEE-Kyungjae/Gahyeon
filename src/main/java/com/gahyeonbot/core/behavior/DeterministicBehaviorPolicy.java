package com.gahyeonbot.core.behavior;

import com.gahyeonbot.core.world.WorldActivity;
import com.gahyeonbot.core.world.WorldStateSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

public class DeterministicBehaviorPolicy {
    private final Clock clock;
    private final ZoneId zone;

    public DeterministicBehaviorPolicy() {
        this(Clock.systemUTC(), ZoneId.of("Asia/Seoul"));
    }

    DeterministicBehaviorPolicy(Clock clock, ZoneId zone) {
        this.clock = clock;
        this.zone = zone;
    }

    public Optional<BehaviorDecision> decide(
            WorldStateSnapshot state,
            WorldDefinition world) {
        if (state.activity() == WorldActivity.ATTENTION
                || state.activity() == WorldActivity.CONVERSATION) {
            return Optional.empty();
        }

        int hour = ZonedDateTime.ofInstant(clock.instant(), zone).getHour();
        if (hour >= 1 && hour < 7) {
            return state.activity() == WorldActivity.SLEEP
                    ? Optional.empty()
                    : Optional.of(new BehaviorDecision(
                            WorldActivity.SLEEP, world.requirePoint("bed")));
        }

        Duration elapsed = Duration.between(state.activityStartedAt(), clock.instant());
        if (elapsed.compareTo(minimumDuration(state.activity())) < 0) return Optional.empty();

        return Optional.of(switch ((int) (state.revision() % 5)) {
            case 0 -> new BehaviorDecision(WorldActivity.WORK, world.requirePoint("desk"));
            case 1 -> new BehaviorDecision(WorldActivity.READ, world.requirePoint("bookshelf"));
            case 2 -> new BehaviorDecision(WorldActivity.LOOK_OUTSIDE, world.requirePoint("window"));
            case 3 -> new BehaviorDecision(WorldActivity.RELAX, world.requirePoint("chair"));
            default -> new BehaviorDecision(WorldActivity.IDLE, world.requirePoint("room-center"));
        });
    }

    private Duration minimumDuration(WorldActivity activity) {
        return switch (activity) {
            case SLEEP -> Duration.ofMinutes(30);
            case WORK, READ -> Duration.ofMinutes(10);
            case SIT, RELAX, LOOK_OUTSIDE -> Duration.ofMinutes(5);
            case WALK -> Duration.ofMinutes(2);
            case IDLE -> Duration.ofMinutes(1);
            case ATTENTION, CONVERSATION -> Duration.ofDays(1);
        };
    }
}
