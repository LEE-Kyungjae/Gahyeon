package com.gahyeonbot.adapters.unreal;

import java.util.ArrayList;
import java.util.List;

/** Incrementally emits speakable sentences without waiting for the full LLM response. */
public final class IncrementalSentenceAccumulator {
    private final int maxCharacters;
    private final StringBuilder pending = new StringBuilder();

    public IncrementalSentenceAccumulator(int maxCharacters) {
        if (maxCharacters < 20) throw new IllegalArgumentException("maxCharacters must be at least 20");
        this.maxCharacters = maxCharacters;
    }

    public List<String> accept(String delta) {
        if (delta == null || delta.isEmpty()) return List.of();
        pending.append(delta);
        return drain(false);
    }

    public List<String> finish() {
        return drain(true);
    }

    private List<String> drain(boolean finish) {
        List<String> ready = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < pending.length(); index++) {
            char value = pending.charAt(index);
            int length = index - start + 1;
            if (sentenceEnd(value)
                    || length >= maxCharacters && safeBreak(value)
                    || length >= maxCharacters * 2) {
                add(ready, pending.substring(start, index + 1));
                start = index + 1;
            }
        }
        if (start > 0) pending.delete(0, start);
        if (finish && !pending.isEmpty()) {
            add(ready, pending.toString());
            pending.setLength(0);
        }
        return List.copyOf(ready);
    }

    private boolean sentenceEnd(char value) {
        return value == '.' || value == '?' || value == '!'
                || value == '。' || value == '？' || value == '！' || value == '\n';
    }

    private boolean safeBreak(char value) {
        return Character.isWhitespace(value) || value == ',' || value == '，';
    }

    private void add(List<String> ready, String value) {
        String normalized = value.trim();
        if (!normalized.isEmpty()) ready.add(normalized);
    }
}
