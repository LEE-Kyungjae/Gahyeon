package com.gahyeonbot.adapters.life;

import com.gahyeonbot.core.life.*;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.repository.CharacterMemoryRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class JpaCharacterMemoryStoreTest {
    @Autowired CharacterMemoryRecordRepository repository;

    @Test
    void returnsOnlyGlobalAndRequestedSubjectAndDeduplicatesConsolidatedFacts() {
        var store = new JpaCharacterMemoryStore(repository);
        var character = new CharacterId("gahyeon");
        var world = new WorldId("gahyeon-home");
        store.append(memory(character, world, null, "공용 기억"));
        store.append(memory(character, world, "actor:42", "42번 기억"));
        store.append(memory(character, world, "actor:77", "77번 비밀"));
        CharacterMemory fact = memory(character, world, "actor:42", "사용자는 차를 좋아한다.");

        assertThat(store.appendIfAbsent(fact)).isTrue();
        assertThat(store.appendIfAbsent(fact)).isFalse();

        assertThat(store.recent(character, world, "actor:42", 20))
                .extracting(CharacterMemory::content)
                .contains("공용 기억", "42번 기억", "사용자는 차를 좋아한다.")
                .doesNotContain("77번 비밀");
    }

    @Test
    void supersedesAContradictingTopicOnlyWhenConfidenceIsCompetitive() {
        var store = new JpaCharacterMemoryStore(repository);
        var character = new CharacterId("gahyeon");
        var world = new WorldId("gahyeon-home");
        Instant now = Instant.parse("2026-08-19T12:00:00Z");
        CharacterMemory original = keyed(character, world, "actor:42", "사용자는 공포영화를 좋아한다.", 0.90, now);
        CharacterMemory weakContradiction = keyed(character, world, "actor:42", "사용자는 공포영화를 싫어한다.", 0.70, now.plusSeconds(1));
        CharacterMemory strongContradiction = keyed(character, world, "actor:42", "사용자는 공포영화를 싫어한다.", 0.92, now.plusSeconds(2));

        assertThat(store.merge(original)).isEqualTo(CharacterMemoryMergeResult.INSERTED);
        assertThat(store.merge(weakContradiction)).isEqualTo(CharacterMemoryMergeResult.REJECTED_LOWER_CONFIDENCE);
        assertThat(store.merge(strongContradiction)).isEqualTo(CharacterMemoryMergeResult.SUPERSEDED);

        assertThat(store.recent(character, world, "actor:42", 20))
                .extracting(CharacterMemory::content)
                .containsExactly("사용자는 공포영화를 싫어한다.");
        assertThat(repository.count()).isEqualTo(2);
    }

    private static CharacterMemory memory(CharacterId character, WorldId world, String subject, String content) {
        Instant now = Instant.parse("2026-08-19T12:00:00Z");
        return new CharacterMemory(0, character, world, subject, CharacterMemoryKind.SEMANTIC,
                content, 0.7, 0.9, 0, null, now, now);
    }

    private static CharacterMemory keyed(CharacterId character, WorldId world, String subject, String content,
            double confidence, Instant now) {
        return new CharacterMemory(0, character, world, subject, CharacterMemoryKind.SEMANTIC,
                "preference.movie.horror", content, 0.8, confidence, 0, null, now, now);
    }
}
