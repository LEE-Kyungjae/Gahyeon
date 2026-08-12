package com.gahyeonbot.services.ai.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Bounded provider-failure cooldown; a post-cooldown request acts as the recovery probe. */
@Component
final class AgentRuntimeAvailability {
    private static final long CLOSED = 0;
    private static final long HALF_OPEN = Long.MAX_VALUE;
    private static final long MINIMUM_COOLDOWN_MILLIS = 100;
    private static final long MAXIMUM_COOLDOWN_MILLIS = 300_000;

    private final long cooldownNanos;
    private final LongSupplier nanoTime;
    private final AtomicLong unavailableUntilNanos = new AtomicLong();

    @Autowired
    AgentRuntimeAvailability(
            @Value("${gahyeon.agent.provider-failure-cooldown-millis:5000}") long cooldownMillis) {
        this(cooldownMillis, System::nanoTime);
    }

    AgentRuntimeAvailability(long cooldownMillis, LongSupplier nanoTime) {
        long bounded = Math.max(MINIMUM_COOLDOWN_MILLIS,
                Math.min(MAXIMUM_COOLDOWN_MILLIS, cooldownMillis));
        this.cooldownNanos = TimeUnit.MILLISECONDS.toNanos(bounded);
        this.nanoTime = nanoTime;
    }

    boolean isReady() {
        long state = unavailableUntilNanos.get();
        return state == CLOSED || state != HALF_OPEN && nanoTime.getAsLong() >= state;
    }

    boolean tryAcquireProviderCall() {
        while (true) {
            long state = unavailableUntilNanos.get();
            if (state == CLOSED) return true;
            if (state == HALF_OPEN || nanoTime.getAsLong() < state) return false;
            if (unavailableUntilNanos.compareAndSet(state, HALF_OPEN)) return true;
        }
    }

    void recordProviderFailure() {
        long now = nanoTime.getAsLong();
        long until = now > Long.MAX_VALUE - cooldownNanos
                ? Long.MAX_VALUE : now + cooldownNanos;
        while (true) {
            long state = unavailableUntilNanos.get();
            long replacement = state == HALF_OPEN || state == CLOSED
                    ? until : Math.max(state, until);
            if (unavailableUntilNanos.compareAndSet(state, replacement)) return;
        }
    }

    void recordProviderSuccess() {
        unavailableUntilNanos.set(CLOSED);
    }
}
