package com.gahyeonbot.application.behavior;

import com.gahyeonbot.core.behavior.DeterministicBehaviorPolicy;
import com.gahyeonbot.core.behavior.GahyeonHomeWorld;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldStateConflictException;
import com.gahyeonbot.core.world.WorldStateUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gahyeon.behavior.enabled", havingValue = "true")
public class BehaviorCoordinator {
    private static final Logger log = LoggerFactory.getLogger(BehaviorCoordinator.class);

    private final WorldStateUseCase worlds;
    private final DeterministicBehaviorPolicy policy;

    public BehaviorCoordinator(WorldStateUseCase worlds, DeterministicBehaviorPolicy policy) {
        this.worlds = worlds;
        this.policy = policy;
    }

    @Scheduled(fixedDelayString = "${gahyeon.behavior.tick-millis:10000}")
    public void tick() {
        var definition = GahyeonHomeWorld.definition();
        var worldId = new WorldId(definition.worldId());
        var current = worlds.current(worldId);
        policy.decide(current, definition).ifPresent(decision -> {
            try {
                worlds.transition(
                        worldId,
                        current.revision(),
                        decision.target().room(),
                        decision.target().position(),
                        decision.activity(),
                        decision.target().id());
            } catch (WorldStateConflictException conflict) {
                log.debug("World state changed before behavior tick; retrying next tick: {}", conflict.getMessage());
            }
        });
    }
}
