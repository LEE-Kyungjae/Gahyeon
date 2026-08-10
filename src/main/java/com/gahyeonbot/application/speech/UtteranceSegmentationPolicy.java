package com.gahyeonbot.application.speech;

public record UtteranceSegmentationPolicy(
        int bytesPerSecond,
        int maxUtteranceSeconds,
        int minimumCapturedBytes,
        long silenceMillis,
        long minimumSpeechMillis,
        long shortSpeechMillis,
        long shortSpeechEndSilenceMillis,
        long preRollMillis,
        int detectorSampleRate) {

    public UtteranceSegmentationPolicy {
        if (bytesPerSecond <= 0 || maxUtteranceSeconds <= 0 || detectorSampleRate <= 0) {
            throw new IllegalArgumentException("audio rates and maximum duration must be positive");
        }
        if (minimumCapturedBytes < 0 || silenceMillis < 0 || minimumSpeechMillis < 0
                || shortSpeechMillis < 0 || shortSpeechEndSilenceMillis < 0 || preRollMillis < 0) {
            throw new IllegalArgumentException("utterance thresholds must not be negative");
        }
    }

    public int maximumCapturedBytes() {
        return Math.multiplyExact(bytesPerSecond, maxUtteranceSeconds);
    }

    public int preRollBytes() {
        return Math.toIntExact(bytesPerSecond * preRollMillis / 1_000);
    }
}
