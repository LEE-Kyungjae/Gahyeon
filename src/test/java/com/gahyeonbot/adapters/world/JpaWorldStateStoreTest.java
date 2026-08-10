package com.gahyeonbot.adapters.world;

import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldStateSnapshot;
import com.gahyeonbot.repository.GahyeonWorldStateRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class JpaWorldStateStoreTest {
    @Autowired GahyeonWorldStateRecordRepository repository;

    @Test
    void restoresPersistedWorldSnapshot() {
        var store = new JpaWorldStateStore(repository);
        var worldId = new WorldId("gahyeon-home");
        var initial = WorldStateSnapshot.initial(worldId, Instant.parse("2026-08-10T07:00:00Z"));

        store.save(initial);

        assertThat(store.find(worldId)).contains(initial);
    }
}
