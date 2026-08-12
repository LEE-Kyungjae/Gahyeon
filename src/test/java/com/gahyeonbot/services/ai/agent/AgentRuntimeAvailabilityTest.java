package com.gahyeonbot.services.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeAvailabilityTest {
    @Test
    void providerFailureOpensForAProbeAfterTheBoundedCooldown() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        var availability = new AgentRuntimeAvailability(5_000, now::get);

        assertThat(availability.isReady()).isTrue();
        availability.recordProviderFailure();
        assertThat(availability.isReady()).isFalse();

        now.addAndGet(4_999_999_999L);
        assertThat(availability.isReady()).isFalse();
        now.incrementAndGet();
        assertThat(availability.isReady()).isTrue();
        assertThat(availability.tryAcquireProviderCall()).isTrue();
        assertThat(availability.tryAcquireProviderCall()).isFalse();
    }

    @Test
    void successClosesTheCircuitImmediately() {
        AtomicLong now = new AtomicLong(100L);
        var availability = new AgentRuntimeAvailability(5_000, now::get);
        availability.recordProviderFailure();

        availability.recordProviderSuccess();

        assertThat(availability.isReady()).isTrue();
        assertThat(availability.tryAcquireProviderCall()).isTrue();
    }

    @Test
    void invalidCooldownValuesAreBounded() {
        AtomicLong now = new AtomicLong(10L);
        var minimum = new AgentRuntimeAvailability(-1, now::get);
        minimum.recordProviderFailure();
        now.addAndGet(99_999_999L);
        assertThat(minimum.isReady()).isFalse();
        now.incrementAndGet();
        assertThat(minimum.isReady()).isTrue();
    }

    @Test
    void failedHalfOpenProbeReopensInsteadOfRemainingStuck() {
        AtomicLong now = new AtomicLong(1_000L);
        var availability = new AgentRuntimeAvailability(100, now::get);
        availability.recordProviderFailure();
        now.addAndGet(100_000_000L);
        assertThat(availability.tryAcquireProviderCall()).isTrue();

        availability.recordProviderFailure();

        assertThat(availability.isReady()).isFalse();
        assertThat(availability.tryAcquireProviderCall()).isFalse();
        now.addAndGet(100_000_000L);
        assertThat(availability.tryAcquireProviderCall()).isTrue();
    }
}
