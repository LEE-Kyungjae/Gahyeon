package com.gahyeonbot.application.behavior;

import com.gahyeonbot.application.world.WorldRuntimeReadiness;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drives Core-owned World actions even when no Presentation client is connected. */
@Component
@ConditionalOnProperty(name = "gahyeon.behavior.enabled", havingValue = "true")
public final class WorldActionExecutionScheduler {
    private final WorldActionCoordinator actions;
    private final List<WorldActionPresentationPresence> presentationPresences;
    private final WorldRuntimeReadiness readiness;

    public WorldActionExecutionScheduler(
            WorldActionCoordinator actions,
            List<WorldActionPresentationPresence> presentationPresences,
            WorldRuntimeReadiness readiness) {
        this.actions = actions;
        this.presentationPresences = List.copyOf(presentationPresences);
        this.readiness = readiness;
    }

    @Scheduled(fixedDelayString = "${gahyeon.world-action.execution-tick-millis:250}")
    public void advance() {
        if (!readiness.isReady()) return;
        actions.advanceReadyActions(worldId -> presentationPresences.stream()
                .anyMatch(presence -> presence.hasRenderer(worldId)));
    }

    @Scheduled(fixedDelayString = "${gahyeon.world-action.timeout-scan-millis:5000}")
    public void expire() {
        if (!readiness.isReady()) return;
        actions.expireTimedOutActions();
    }
}
