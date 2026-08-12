package com.gahyeonbot.application.behavior;

import com.gahyeonbot.core.behavior.DeterministicBehaviorPolicy;
import com.gahyeonbot.core.behavior.GahyeonHomeWorld;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldStateUseCase;
import com.gahyeonbot.application.world.WorldRuntimeReadiness;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gahyeon.behavior.enabled", havingValue = "true")
public class BehaviorCoordinator {
    private final WorldStateUseCase worlds;
    private final DeterministicBehaviorPolicy policy;
    private final WorldActionCoordinator actions;
    private final WorldRuntimeReadiness readiness;

    public BehaviorCoordinator(
            WorldStateUseCase worlds,
            DeterministicBehaviorPolicy policy,
            WorldActionCoordinator actions,
            WorldRuntimeReadiness readiness) {
        this.worlds = worlds;
        this.policy = policy;
        this.actions = actions;
        this.readiness = readiness;
    }

    @Scheduled(fixedDelayString = "${gahyeon.behavior.tick-millis:10000}")
    public void tick() {
        if (!readiness.isReady()) return;
        var definition = GahyeonHomeWorld.definition();
        var worldId = new WorldId(definition.worldId());
        var current = worlds.current(worldId);
        policy.decide(current, definition).ifPresent(decision ->
                actions.request(current, decision));
    }
}
