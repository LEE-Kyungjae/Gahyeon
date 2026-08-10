package com.gahyeonbot.core.identity;

/** Gahyeon's platform-independent internal identity. */
public record ActorId(long value) {
    public ActorId {
        if (value <= 0) throw new IllegalArgumentException("actorId는 양수여야 합니다.");
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
