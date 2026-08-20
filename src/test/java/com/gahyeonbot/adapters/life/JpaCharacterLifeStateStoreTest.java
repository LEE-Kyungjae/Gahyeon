package com.gahyeonbot.adapters.life;

import com.gahyeonbot.core.life.CharacterId;
import com.gahyeonbot.core.life.CharacterLifeState;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.repository.CharacterLifeStateRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class JpaCharacterLifeStateStoreTest {
    @Autowired CharacterLifeStateRecordRepository repository;

    @Test
    void persistsCharactersIndependentlyInsideOneWorld() {
        var store = new JpaCharacterLifeStateStore(repository);
        var world = new WorldId("gahyeon-home");
        var now = Instant.parse("2026-08-19T05:00:00Z");
        var gahyeon = CharacterLifeState.initial(new CharacterId("gahyeon"), world, now);
        var diana = CharacterLifeState.initial(new CharacterId("diana"), world, now);

        store.save(gahyeon);
        store.save(diana);

        assertThat(store.find(gahyeon.characterId(), world)).contains(gahyeon);
        assertThat(store.find(diana.characterId(), world)).contains(diana);
        assertThat(repository.count()).isEqualTo(2);
    }
}
