package com.gahyeonbot.core.world;

public class WorldStateConflictException extends RuntimeException {
    public WorldStateConflictException(long expected, long actual) {
        super("World State revision 충돌: expected=" + expected + ", actual=" + actual);
    }
}
