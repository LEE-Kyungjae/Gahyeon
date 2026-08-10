package com.gahyeonbot.application.speech;

/** Detects speech in one packet of PCM audio. Implementations own provider resources. */
public interface VoiceActivityDetector extends AutoCloseable {
    Detection detect(byte[] pcm);

    @Override
    default void close() {}

    record Detection(boolean voice, long voiceSamples) {
        public Detection {
            if (voiceSamples < 0) throw new IllegalArgumentException("voiceSamples must not be negative");
        }
    }
}
