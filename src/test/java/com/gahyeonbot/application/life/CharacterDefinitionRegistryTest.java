package com.gahyeonbot.application.life;

import com.gahyeonbot.core.life.CharacterId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterDefinitionRegistryTest {
    @Test
    void keepsGahyeonPrimaryWhileAllowingCatalogGrowthWithoutRuntimeChanges() {
        CharacterCatalogProperties properties = CharacterCatalogProperties.standard();
        var guest = new CharacterCatalogProperties.Entry();
        guest.setId("guest-three");
        guest.setDisplayName("세 번째 인격");
        guest.setPersonaPrompt("prompts/characters/guest-three.txt");
        guest.setVoiceProfile("guest-three.voice");
        guest.setExpressionProfile("guest-three.expression");
        guest.setInitiativeThreshold(0.75);
        guest.setSocialDriftPerHour(0.03);
        guest.setCuriosityDriftPerHour(0.05);
        guest.setRestDriftPerHour(0.02);
        properties.getCatalog().add(guest);

        var registry = new CharacterDefinitionRegistry(properties);

        assertThat(registry.primary().id()).isEqualTo(new CharacterId("gahyeon"));
        assertThat(registry.primary().autonomousEnabled()).isTrue();
        assertThat(registry.require(new CharacterId("guest-three")).primary()).isFalse();
        assertThat(registry.require(new CharacterId("guest-three")).autonomousEnabled()).isFalse();
        assertThat(registry.all()).hasSize(5);
    }
}
