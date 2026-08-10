package com.gahyeonbot.core.event;

public record EventScope(EventScopeType type, String id) {
    public EventScope {
        if (type == null) throw new IllegalArgumentException("event scope type이 필요합니다.");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("event scope ID가 필요합니다.");
    }

    public static EventScope session(String sessionId) {
        return new EventScope(EventScopeType.SESSION, sessionId);
    }

    public static EventScope world(String worldId) {
        return new EventScope(EventScopeType.WORLD, worldId);
    }
}
