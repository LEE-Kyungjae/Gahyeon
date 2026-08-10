package com.gahyeonbot.core.memory;

import java.util.List;

public record MemorySnapshot(String summary, List<MemoryMessage> recentMessages) {
    public static final MemorySnapshot EMPTY = new MemorySnapshot("", List.of());

    public MemorySnapshot {
        summary = summary == null ? "" : summary.trim();
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
    }
}
