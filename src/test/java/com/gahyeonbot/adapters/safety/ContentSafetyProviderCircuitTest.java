package com.gahyeonbot.adapters.safety;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ContentSafetyProviderCircuitTest {
    @Test
    void failureFastFailsUntilExactlyOneRecoveryProbeCanRun() {
        AtomicLong now = new AtomicLong(10L);
        var circuit = new ContentSafetyProviderCircuit(1_000, now::get);
        assertThat(circuit.tryAcquire()).isTrue();
        circuit.failure();

        assertThat(circuit.tryAcquire()).isFalse();
        now.addAndGet(1_000_000_000L);
        assertThat(circuit.tryAcquire()).isTrue();
        assertThat(circuit.tryAcquire()).isFalse();
    }

    @Test
    void successfulProbeClosesAndFailedProbeReopensTheCircuit() {
        AtomicLong now = new AtomicLong(100L);
        var circuit = new ContentSafetyProviderCircuit(1_000, now::get);
        circuit.failure();
        now.addAndGet(1_000_000_000L);
        assertThat(circuit.tryAcquire()).isTrue();
        circuit.failure();
        assertThat(circuit.tryAcquire()).isFalse();
        now.addAndGet(1_000_000_000L);
        assertThat(circuit.tryAcquire()).isTrue();
        circuit.success();
        assertThat(circuit.tryAcquire()).isTrue();
    }
}
