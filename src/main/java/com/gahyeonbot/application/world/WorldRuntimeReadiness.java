package com.gahyeonbot.application.world;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/** Process-local gate that prevents autonomous execution before restart reconciliation. */
@Component
@ConditionalOnExpression("${gahyeon.behavior.enabled:false} || ${gahyeon.life.enabled:false}")
public final class WorldRuntimeReadiness {
    private final AtomicBoolean ready = new AtomicBoolean();

    public boolean isReady() {
        return ready.get();
    }

    public void markReady() {
        ready.set(true);
    }
}
