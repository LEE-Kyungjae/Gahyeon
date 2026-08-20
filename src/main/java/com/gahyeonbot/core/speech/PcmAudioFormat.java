package com.gahyeonbot.core.speech;

public record PcmAudioFormat(int sampleRate, int channels, int bitsPerSample, String encoding) {
    public static final PcmAudioFormat QWEN_MONO_24K_S16LE =
            new PcmAudioFormat(24_000, 1, 16, "s16le");

    public PcmAudioFormat {
        if (sampleRate < 8_000 || sampleRate > 192_000 || channels < 1 || channels > 2
                || bitsPerSample != 16 || !"s16le".equals(encoding)) {
            throw new IllegalArgumentException("unsupported PCM audio format");
        }
    }

    public int bytesPerFrame() {
        return channels * (bitsPerSample / 8);
    }
}
