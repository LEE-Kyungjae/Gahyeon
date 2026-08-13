package com.gahyeonbot.services.ai;

import com.gahyeonbot.core.identity.ActorId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActorLockRegistryTest {
    @Test
    void keepsAdmissionLockStorageBoundedAcrossUnlimitedActors() {
        var registry = new ActorLockRegistry(256);
        var locks = new HashSet<>();

        for (long actor = 1; actor <= 100_000; actor++) {
            locks.add(registry.lockFor(new ActorId(actor)));
        }

        assertThat(registry.capacity()).isEqualTo(256);
        assertThat(locks).hasSize(256);
        assertThat(registry.lockFor(new ActorId(42)))
                .isSameAs(registry.lockFor(new ActorId(42)));
    }

    @Test
    void rejectsInvalidStripeCountsAndMissingActors() {
        assertThatThrownBy(() -> new ActorLockRegistry(255))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActorLockRegistry(256).lockFor(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
