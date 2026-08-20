package com.gahyeonbot.core.life;

import java.time.Instant;

public record LifeStimulus(
        String type,
        double importance,
        String subject,
        boolean expiresIfIgnored,
        Instant observedAt
) {
    public LifeStimulus {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("stimulus type is required");
        if (!Double.isFinite(importance) || importance < 0 || importance > 1) {
            throw new IllegalArgumentException("importance must be between 0 and 1");
        }
        if (subject != null && subject.length() > 200) throw new IllegalArgumentException("stimulus subject is too long");
        if (observedAt == null) throw new IllegalArgumentException("observedAt is required");
        type = type.trim().toLowerCase();
        subject = subject == null || subject.isBlank() ? null : subject.trim();
    }

    public static LifeStimulus idleTick(Instant now) {
        return new LifeStimulus("time.elapsed", 0, null, false, now);
    }
}
