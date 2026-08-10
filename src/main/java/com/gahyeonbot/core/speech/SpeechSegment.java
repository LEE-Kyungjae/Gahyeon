package com.gahyeonbot.core.speech;

public record SpeechSegment(int index, String text) {
    public SpeechSegment {
        if (index < 0) throw new IllegalArgumentException("segment index는 0 이상이어야 합니다.");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("segment text가 필요합니다.");
        text = text.trim();
    }
}
