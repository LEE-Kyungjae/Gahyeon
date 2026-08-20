package com.gahyeonbot.application.life;

import com.gahyeonbot.application.world.WorldRuntimeReadiness;
import com.gahyeonbot.core.world.WorldId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gahyeon.life.enabled", havingValue = "true")
public final class CharacterLifeCoordinator {
    private static final WorldId HOME = new WorldId("gahyeon-home");
    private final CharacterDefinitionRegistry characters;
    private final CharacterLifeService life;
    private final WorldRuntimeReadiness readiness;

    public CharacterLifeCoordinator(CharacterDefinitionRegistry characters, CharacterLifeService life, WorldRuntimeReadiness readiness) {
        this.characters = characters;
        this.life = life;
        this.readiness = readiness;
    }

    @Scheduled(fixedDelayString = "${gahyeon.life.tick-millis:60000}")
    public void tick() {
        if (!readiness.isReady()) return;
        for (var character : characters.all()) {
            if (character.autonomousEnabled()) life.tick(character.id(), HOME);
        }
    }
}
