package com.gahyeonbot.adapters.discord.voice;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** Session-local admission policy for non-repeating progress speech. */
final class VoiceAcknowledgementPolicy {
    private boolean inFlight;
    private long lastStartedAt = Long.MIN_VALUE;
    private int nextIndex;

    synchronized Optional<Lease> tryAcquire(
            long now,
            long cooldownMillis,
            String legacyOverride,
            List<String> configuredMessages) {
        List<String> messages = normalizedMessages(legacyOverride, configuredMessages);
        if (inFlight || messages.isEmpty()) return Optional.empty();
        if (lastStartedAt != Long.MIN_VALUE
                && now - lastStartedAt < Math.max(0, cooldownMillis)) {
            return Optional.empty();
        }
        String message = messages.get(Math.floorMod(nextIndex++, messages.size()));
        inFlight = true;
        lastStartedAt = now;
        return Optional.of(new Lease(message));
    }

    synchronized boolean hasMessages(String legacyOverride, List<String> configuredMessages) {
        return !normalizedMessages(legacyOverride, configuredMessages).isEmpty();
    }

    private synchronized void release() {
        inFlight = false;
    }

    private static List<String> normalizedMessages(
            String legacyOverride,
            List<String> configuredMessages) {
        var unique = new LinkedHashSet<String>();
        if (legacyOverride != null && !legacyOverride.isBlank()) {
            unique.add(legacyOverride.trim());
        } else if (configuredMessages != null) {
            configuredMessages.stream()
                    .filter(message -> message != null && !message.isBlank())
                    .map(String::trim)
                    .forEach(unique::add);
        }
        return new ArrayList<>(unique);
    }

    final class Lease implements AutoCloseable {
        private final String message;
        private boolean closed;

        private Lease(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }

        @Override
        public void close() {
            synchronized (VoiceAcknowledgementPolicy.this) {
                if (closed) return;
                closed = true;
                release();
            }
        }
    }
}
