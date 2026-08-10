package com.gahyeonbot.core.world;

public record WorldPosition(double x, double y, double z) {
    public WorldPosition {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("World 좌표는 유한한 숫자여야 합니다.");
        }
    }

    public static WorldPosition origin() {
        return new WorldPosition(0, 0, 0);
    }
}
