package com.gahyeonbot.core.memory;

public record MemoryMessage(MemoryRole role, String content) {
    public MemoryMessage {
        if (role == null) throw new IllegalArgumentException("memory role is required");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("memory content is required");
        content = content.trim();
    }
}
