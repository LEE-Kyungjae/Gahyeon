package com.gahyeonbot.application.world;

import com.gahyeonbot.core.behavior.GahyeonHomeWorld;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldStateUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Restores process-local World activities before autonomous Behavior resumes. */
@Component
@ConditionalOnExpression("${gahyeon.behavior.enabled:false} || ${gahyeon.life.enabled:false}")
public final class WorldStateRecoveryCoordinator {
    private final WorldStateUseCase worlds;
    private final WorldRuntimeReadiness readiness;

    public WorldStateRecoveryCoordinator(
            WorldStateUseCase worlds,
            WorldRuntimeReadiness readiness) {
        this.worlds = worlds;
        this.readiness = readiness;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (readiness.isReady()) return;
        worlds.recoverAfterRestart(new WorldId(GahyeonHomeWorld.WORLD_ID));
        readiness.markReady();
    }
}
