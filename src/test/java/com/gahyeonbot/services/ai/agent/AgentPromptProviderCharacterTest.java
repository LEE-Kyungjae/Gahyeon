package com.gahyeonbot.services.ai.agent;

import com.gahyeonbot.application.life.*;
import com.gahyeonbot.core.life.*;
import com.gahyeonbot.core.world.WorldId;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AgentPromptProviderCharacterTest {
    @Test
    void selectsOnlyTheRequestedCharactersPersonaAndMemory() {
        var memories = new InMemoryStore();
        memories.append(memory("gahyeon", "가현의 비밀 기억"));
        memories.append(memory("diana", "다이애나의 독립 기억"));
        var provider = new AgentPromptProvider();
        provider.load();
        provider.configureCharacters(
                new CharacterDefinitionRegistry(CharacterCatalogProperties.standard()), memories);

        String prompt = provider.systemPrompt("공용 기억은 들어오면 안 됨",
                "character:diana:gahyeon-home:actor:42|desktop:room-1");

        assertThat(prompt).contains("너는 다이애나다", "다이애나의 독립 기억")
                .doesNotContain("너는 가현이다", "가현의 비밀 기억", "공용 기억은 들어오면 안 됨");
    }

    @Test
    void characterSessionsNeverUseTheLegacyActorWideMemory() {
        assertThat(DefaultAgentRuntime.usesSharedActorMemory(
                "character:diana:gahyeon-home:actor:42|desktop:room-1")).isFalse();
        assertThat(DefaultAgentRuntime.usesSharedActorMemory("discord:guild:channel")).isTrue();
    }

    @Test
    void selectsOnlyGlobalAndCurrentUserMemoriesForTheSameCharacter() {
        var memories = new InMemoryStore();
        memories.append(memory("gahyeon", null, "공용 세계 기억"));
        memories.append(memory("gahyeon", "actor:42", "42번 사용자 기억"));
        memories.append(memory("gahyeon", "actor:77", "77번 사용자 비밀"));
        var provider = new AgentPromptProvider();
        provider.load();
        provider.configureCharacters(
                new CharacterDefinitionRegistry(CharacterCatalogProperties.standard()), memories);

        String prompt = provider.systemPrompt(null,
                "character:gahyeon:gahyeon-home:actor:42|desktop:room-1");

        assertThat(prompt).contains("공용 세계 기억", "42번 사용자 기억")
                .doesNotContain("77번 사용자 비밀");
    }

    @Test
    void includesOnlyTheSelectedCharactersRelationshipWithTheCurrentUser() {
        var provider = new AgentPromptProvider();
        provider.load();
        provider.configureCharacters(
                new CharacterDefinitionRegistry(CharacterCatalogProperties.standard()), new InMemoryStore());
        provider.configureRelationships(new CharacterRelationshipStore() {
            public Optional<CharacterRelationshipState> find(CharacterId characterId, WorldId worldId, String subjectId) {
                if (characterId.value().equals("diana") && subjectId.equals("actor:42")) {
                    return Optional.of(new CharacterRelationshipState(characterId, worldId, subjectId, 3,
                            0.4, 0.8, 0.7, 0.1, Instant.EPOCH, Instant.EPOCH));
                }
                return Optional.empty();
            }
            public CharacterRelationshipState save(CharacterRelationshipState state) { return state; }
        });

        String prompt = provider.systemPrompt(null,
                "character:diana:gahyeon-home:actor:42|desktop:room-1");

        assertThat(prompt).contains("familiarity=0.400 trust=0.800 affinity=0.700 tension=0.100");
    }

    private static CharacterMemory memory(String id, String content) {
        return new CharacterMemory(0, new CharacterId(id), new WorldId("gahyeon-home"),
                "episodic", content, 0.7, Instant.EPOCH);
    }

    private static CharacterMemory memory(String id, String subject, String content) {
        return new CharacterMemory(0, new CharacterId(id), new WorldId("gahyeon-home"), subject,
                CharacterMemoryKind.EPISODIC, content, 0.7, 0.9, 0, null, Instant.EPOCH, Instant.EPOCH);
    }

    private static final class InMemoryStore implements CharacterMemoryStore {
        private final List<CharacterMemory> values = new ArrayList<>();
        public CharacterMemory append(CharacterMemory memory) { values.add(memory); return memory; }
        public List<CharacterMemory> recent(CharacterId characterId, WorldId worldId, int limit) {
            return values.stream().filter(value -> value.characterId().equals(characterId)
                    && value.worldId().equals(worldId)).limit(limit).toList();
        }
    }
}
