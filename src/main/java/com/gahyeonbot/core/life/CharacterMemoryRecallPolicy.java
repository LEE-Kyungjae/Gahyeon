package com.gahyeonbot.core.life;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public final class CharacterMemoryRecallPolicy {
    public List<CharacterMemory> rank(List<CharacterMemory> candidates, Instant now, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return candidates.stream()
                .filter(memory -> !memory.expiredAt(now))
                .sorted(Comparator.comparingDouble((CharacterMemory memory) -> score(memory, now)).reversed()
                        .thenComparing(CharacterMemory::createdAt, Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    double score(CharacterMemory memory, Instant now) {
        double ageHours = Math.max(0, Duration.between(memory.createdAt(), now).toMinutes() / 60d);
        double recency = Math.exp(-ageHours / halfLifeHours(memory.kind()));
        double prospectiveBoost = memory.kind() == CharacterMemoryKind.PROSPECTIVE ? 0.18 : 0;
        return memory.importance() * 0.34 + memory.confidence() * 0.24
                + Math.abs(memory.emotionalWeight()) * 0.14 + recency * 0.28 + prospectiveBoost;
    }

    private double halfLifeHours(CharacterMemoryKind kind) {
        return switch (kind) {
            case WORKING -> 24;
            case EPISODIC -> 24 * 14;
            case SEMANTIC, RELATIONSHIP -> 24 * 120;
            case PROSPECTIVE -> 24 * 365;
            case REFLECTION, UTTERANCE -> 24 * 7;
        };
    }
}
