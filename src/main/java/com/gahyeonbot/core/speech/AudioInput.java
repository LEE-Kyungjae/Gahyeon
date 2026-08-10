package com.gahyeonbot.core.speech;

import java.util.Arrays;

public record AudioInput(byte[] data, String mediaType) {
    public AudioInput {
        if (data == null || data.length == 0) throw new IllegalArgumentException("audio data가 필요합니다.");
        data = Arrays.copyOf(data, data.length);
        mediaType = mediaType == null || mediaType.isBlank() ? "audio/wav" : mediaType.trim();
    }

    @Override
    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }
}
