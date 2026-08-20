package com.gahyeonbot.application.life;

import com.gahyeonbot.application.world.WorldRuntimeReadiness;
import com.gahyeonbot.core.life.CharacterId;
import com.gahyeonbot.core.world.WorldId;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CharacterLifeCoordinatorTest {
    @Test
    void ticksOnlyExplicitlyAutonomousCharacters() {
        var readiness = new WorldRuntimeReadiness();
        readiness.markReady();
        var life = mock(CharacterLifeService.class);
        var coordinator = new CharacterLifeCoordinator(
                new CharacterDefinitionRegistry(CharacterCatalogProperties.standard()), life, readiness);

        coordinator.tick();

        var home = new WorldId("gahyeon-home");
        verify(life).tick(new CharacterId("gahyeon"), home);
        verify(life, never()).tick(new CharacterId("diana"), home);
        verify(life, never()).tick(new CharacterId("stella-lily"), home);
        verify(life, never()).tick(new CharacterId("ururu"), home);
    }
}
