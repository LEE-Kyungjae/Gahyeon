package com.gahyeonbot.application.life;

import com.gahyeonbot.core.life.CharacterDefinition;
import com.gahyeonbot.core.life.CharacterId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public final class CharacterDefinitionRegistry {
    private final Map<CharacterId, CharacterDefinition> definitions;

    public CharacterDefinitionRegistry(CharacterCatalogProperties properties) {
        if (properties.getCatalog() == null || properties.getCatalog().isEmpty()) {
            throw new IllegalStateException("at least one character must be configured");
        }
        CharacterId primaryId = new CharacterId(properties.getPrimaryId());
        definitions = properties.getCatalog().stream().map(entry -> new CharacterDefinition(
                        new CharacterId(entry.getId()), entry.getDisplayName(),
                        primaryId.equals(new CharacterId(entry.getId())),
                        entry.getPersonaPrompt(),
                        entry.getVoiceProfile(), entry.getExpressionProfile(), entry.isAutonomousEnabled(), entry.getInitiativeThreshold(),
                        entry.getInitiativeCooldown(), entry.getSocialDriftPerHour(),
                        entry.getCuriosityDriftPerHour(), entry.getRestDriftPerHour()))
                .collect(Collectors.toUnmodifiableMap(CharacterDefinition::id, Function.identity()));
        if (!definitions.containsKey(primaryId)) throw new IllegalStateException("primary character is not configured");
    }

    public CharacterDefinition require(CharacterId id) {
        CharacterDefinition definition = definitions.get(id);
        if (definition == null) throw new IllegalArgumentException("unknown character: " + id.value());
        return definition;
    }

    public List<CharacterDefinition> all() {
        return definitions.values().stream().sorted((left, right) -> left.id().value().compareTo(right.id().value())).toList();
    }

    public CharacterDefinition primary() {
        return definitions.values().stream().filter(CharacterDefinition::primary).findFirst().orElseThrow();
    }
}
