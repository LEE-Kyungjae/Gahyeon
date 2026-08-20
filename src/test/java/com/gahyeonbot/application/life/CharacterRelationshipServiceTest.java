package com.gahyeonbot.application.life;

import com.gahyeonbot.core.life.*;
import com.gahyeonbot.core.world.WorldId;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class CharacterRelationshipServiceTest {
    @Test
    void accumulatesPositiveAndNegativeEvidenceWithoutCrossingCharacterOrUserBoundaries() {
        Instant now = Instant.parse("2026-08-19T12:00:00Z");
        var store = new InMemoryStore();
        var service = new CharacterRelationshipService(
                new CharacterDefinitionRegistry(CharacterCatalogProperties.standard()), store,
                Clock.fixed(now, ZoneOffset.UTC));
        var positive = new CharacterMemoryCandidate(CharacterMemoryKind.RELATIONSHIP,
                "relationship.comfort", "사용자는 가현에게 편하게 농담한다.", 0.8, 0.9, 0.7, null);
        var negative = new CharacterMemoryCandidate(CharacterMemoryKind.RELATIONSHIP,
                "relationship.tension", "사용자는 다이애나의 답변에 불편함을 표현했다.", 0.7, 0.9, -0.8, null);

        CharacterRelationshipState gahyeon = service.apply(
                new CharacterId("gahyeon"), new WorldId("home"), "actor:42", positive);
        CharacterRelationshipState diana = service.apply(
                new CharacterId("diana"), new WorldId("home"), "actor:77", negative);

        assertThat(gahyeon.affinity()).isGreaterThan(0.35);
        assertThat(gahyeon.trust()).isGreaterThan(0.50);
        assertThat(diana.tension()).isGreaterThan(0);
        assertThat(diana.trust()).isLessThan(0.50);
        assertThat(service.current(new CharacterId("gahyeon"), new WorldId("home"), "actor:77").revision()).isZero();
        assertThat(store.values).hasSize(3);
    }

    private static final class InMemoryStore implements CharacterRelationshipStore {
        final Map<String, CharacterRelationshipState> values = new HashMap<>();
        public Optional<CharacterRelationshipState> find(CharacterId characterId, WorldId worldId, String subjectId) {
            return Optional.ofNullable(values.get(key(characterId, worldId, subjectId)));
        }
        public CharacterRelationshipState save(CharacterRelationshipState state) {
            values.put(key(state.characterId(), state.worldId(), state.subjectId()), state);
            return state;
        }
        private String key(CharacterId characterId, WorldId worldId, String subjectId) {
            return characterId.value() + "|" + worldId.value() + "|" + subjectId;
        }
    }
}
