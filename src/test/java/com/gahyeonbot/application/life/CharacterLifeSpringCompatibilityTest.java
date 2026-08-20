package com.gahyeonbot.application.life;

import com.gahyeonbot.adapters.life.JpaCharacterMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CharacterLifeSpringCompatibilityTest {
    @Test
    void proxyBackedLifeComponentsRemainSubclassable() {
        List.of(
                CharacterRelationshipService.class,
                CharacterConversationMemoryListener.class,
                CharacterMemoryConsolidationListener.class,
                CharacterCognitionListener.class,
                JpaCharacterMemoryStore.class
        ).forEach(type -> assertThat(Modifier.isFinal(type.getModifiers()))
                .as("%s must remain proxyable by Spring", type.getSimpleName())
                .isFalse());
    }

    @Test
    void multiConstructorLifeComponentsDeclareTheRuntimeInjectionConstructor() {
        List.of(
                CharacterLifeService.class,
                CharacterRelationshipService.class,
                CharacterCognitionService.class,
                CharacterConversationMemoryListener.class,
                CharacterMemoryConsolidationService.class
        ).forEach(type -> assertThat(List.of(type.getDeclaredConstructors()).stream()
                .anyMatch(constructor -> constructor.isAnnotationPresent(Autowired.class)))
                .as("%s must identify its Spring runtime constructor", type.getSimpleName())
                .isTrue());
    }
}
