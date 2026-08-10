package com.gahyeonbot.application.speech;

import java.io.ByteArrayOutputStream;
import java.util.Optional;

/**
 * Platform-neutral streaming utterance state machine. A client adapter supplies
 * normalized PCM packets and may optionally supply a VAD implementation.
 */
public final class StreamingUtteranceAccumulator implements AutoCloseable {
    private final UtteranceSegmentationPolicy policy;
    private final VoiceActivityDetector detector;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final ByteArrayOutputStream preRoll = new ByteArrayOutputStream();
    private long lastVoiceAt;
    private long voiceSamples;
    private boolean speechStarted;

    public StreamingUtteranceAccumulator(
            UtteranceSegmentationPolicy policy,
            VoiceActivityDetector detector,
            long startedAtMillis) {
        this.policy = policy;
        this.detector = detector;
        this.lastVoiceAt = startedAtMillis;
        this.speechStarted = detector == null;
    }

    public void accept(byte[] pcm, long nowMillis) {
        if (pcm == null || pcm.length == 0) return;
        if (detector == null) {
            appendCaptured(pcm);
            lastVoiceAt = nowMillis;
            return;
        }

        VoiceActivityDetector.Detection detection = detector.detect(pcm);
        if (!speechStarted) {
            if (!detection.voice()) {
                appendPreRoll(pcm);
                return;
            }
            speechStarted = true;
            appendCaptured(preRoll.toByteArray());
            preRoll.reset();
        }
        appendCaptured(pcm);
        if (detection.voice()) {
            lastVoiceAt = nowMillis;
            voiceSamples += detection.voiceSamples();
        }
    }

    public Optional<CompletedUtterance> poll(long nowMillis) {
        long speechMillis = detector == null
                ? Long.MAX_VALUE
                : voiceSamples * 1_000 / policy.detectorSampleRate();
        long requiredSilence = policy.silenceMillis();
        if (detector != null && speechMillis < policy.shortSpeechMillis()) {
            requiredSilence = Math.max(requiredSilence, policy.shortSpeechEndSilenceMillis());
        }
        boolean valid = speechStarted
                && captured.size() >= policy.minimumCapturedBytes()
                && speechMillis >= policy.minimumSpeechMillis();
        boolean maximumLength = captured.size() >= policy.maximumCapturedBytes();
        if (!valid || (nowMillis - lastVoiceAt < requiredSilence && !maximumLength)) {
            return Optional.empty();
        }

        byte[] pcm = captured.toByteArray();
        long capturedMillis = pcm.length * 1_000L / policy.bytesPerSecond();
        reset(nowMillis);
        return Optional.of(new CompletedUtterance(pcm, capturedMillis, speechMillis));
    }

    private void appendCaptured(byte[] pcm) {
        int remaining = policy.maximumCapturedBytes() - captured.size();
        if (remaining > 0) captured.write(pcm, 0, Math.min(remaining, pcm.length));
    }

    private void appendPreRoll(byte[] packet) {
        int maximum = policy.preRollBytes();
        if (maximum == 0) return;
        preRoll.writeBytes(packet);
        if (preRoll.size() <= maximum) return;
        byte[] bytes = preRoll.toByteArray();
        preRoll.reset();
        preRoll.write(bytes, bytes.length - maximum, maximum);
    }

    private void reset(long nowMillis) {
        captured.reset();
        preRoll.reset();
        voiceSamples = 0;
        speechStarted = detector == null;
        lastVoiceAt = nowMillis;
    }

    @Override
    public void close() {
        if (detector != null) detector.close();
    }

    public record CompletedUtterance(byte[] pcm, long capturedAudioMillis, long detectedSpeechMillis) {
        public CompletedUtterance {
            pcm = pcm.clone();
        }

        @Override
        public byte[] pcm() {
            return pcm.clone();
        }
    }
}
