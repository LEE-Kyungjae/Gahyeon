package com.gahyeonbot.core.speech;

import java.util.Arrays;

public record AudioOutput(byte[] data, String mediaType, String fileExtension) {
    public AudioOutput {
        if (data == null || data.length == 0) throw new IllegalArgumentException("audio output이 비어 있습니다.");
        data = Arrays.copyOf(data, data.length);
        if (mediaType == null || mediaType.isBlank()) throw new IllegalArgumentException("mediaType이 필요합니다.");
        mediaType = mediaType.trim().toLowerCase();
        if (fileExtension == null || !fileExtension.matches("[a-zA-Z0-9]+")) {
            throw new IllegalArgumentException("fileExtension이 올바르지 않습니다.");
        }
        fileExtension = fileExtension.toLowerCase();
    }

    @Override
    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }
}
