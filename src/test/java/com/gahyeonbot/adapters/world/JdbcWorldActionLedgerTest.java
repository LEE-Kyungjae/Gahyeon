package com.gahyeonbot.adapters.world;

import com.gahyeonbot.application.behavior.WorldActionLedger.ActionStatus;
import com.gahyeonbot.application.behavior.WorldActionLedger.PendingAction;
import com.gahyeonbot.core.world.WorldActivity;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldPosition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class JdbcWorldActionLedgerTest {
    @Autowired JdbcTemplate jdbc;

    @Test
    void supportsTheDurableActionLifecycleOnTheDevelopmentDatabase() {
        var ledger = new JdbcWorldActionLedger(jdbc);
        var now = Instant.parse("2026-08-11T10:00:00Z");
        var action = action("action-1", "gahyeon-home", now);

        assertThat(ledger.create(action)).isTrue();
        assertThat(ledger.create(action)).isFalse();
        assertThat(ledger.findPending(action.worldId())).isPresent();
        assertThat(ledger.findReady(now.plusSeconds(2), 10)).hasSize(1);
        assertThat(ledger.claim(action.actionId())).isTrue();
        assertThat(ledger.finishClaimed(action.actionId(), ActionStatus.COMPLETED,
                "core_execution", now.plusSeconds(2))).isTrue();

        assertThat(ledger.findPending(action.worldId())).isEmpty();
        assertThat(ledger.countPending()).isZero();
        assertThat(ledger.find(action.actionId())).get()
                .extracting(record -> record.status())
                .isEqualTo(ActionStatus.COMPLETED);
    }

    @Test
    void allowsOnlyOnePendingActionPerWorld() {
        var ledger = new JdbcWorldActionLedger(jdbc);
        var now = Instant.parse("2026-08-11T10:00:00Z");

        assertThat(ledger.create(action("action-1", "gahyeon-home", now))).isTrue();
        assertThat(ledger.create(action("action-2", "gahyeon-home", now))).isFalse();
    }

    private PendingAction action(String actionId, String worldId, Instant now) {
        return new PendingAction(actionId, new WorldId(worldId), 0,
                new WorldPosition(0, 0, 0), "workspace",
                new WorldPosition(1, 2, 3), WorldActivity.WORK, "desk",
                now, now.plusSeconds(1), now.plusSeconds(30));
    }
}
