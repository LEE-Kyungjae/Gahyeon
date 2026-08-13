package com.gahyeonbot.application.speech;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Provider-neutral, utterance-scoped streaming speech recognition capability.
 *
 * <p>The application owns stream identity and lifecycle. Implementations own provider credentials,
 * codecs/resampling and network details. A provider must either accept a complete ordered sequence
 * of chunks or fail the utterance; silently dropping audio is forbidden.</p>
 */
public interface StreamingTranscriptionPort {
    boolean isReady();

    Session open(StartRequest request, ResultListener listener);

    interface Session extends AutoCloseable {
        OfferResult offer(AudioChunk chunk);

        void finish();

        void cancel(CancelReason reason);

        @Override
        default void close() {
            cancel(CancelReason.CLIENT_RESET);
        }
    }

    interface ResultListener {
        void onPartial(long resultSequence, String text, double stability);

        void onFinal(long resultSequence, String text, String language);

        void onError(ErrorCode code, boolean recoverable);
    }

    enum OfferResult {
        ACCEPTED,
        BACKPRESSURE
    }

    enum CancelReason {
        BARGE_IN,
        CLIENT_RESET,
        BACKPRESSURE,
        TIMEOUT,
        CAPTURE_ERROR
    }

    enum ErrorCode {
        PROVIDER_UNAVAILABLE,
        PROVIDER_TIMEOUT,
        PROVIDER_ERROR
    }

    record StreamKey(String sessionId, String streamId, long generation) {
        private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

        public StreamKey {
            requireId("sessionId", sessionId);
            requireId("streamId", streamId);
            if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        }

        private static void requireId(String name, String value) {
            if (value == null || !ID.matcher(value).matches()) {
                throw new IllegalArgumentException(name + " is invalid");
            }
        }
    }

    record AudioFormat(String encoding, int sampleRate, int channels, int framesPerChunk) {
        public AudioFormat {
            if (!"float32le".equals(encoding)) {
                throw new IllegalArgumentException("encoding must be float32le");
            }
            if (sampleRate < 8_000 || sampleRate > 192_000) {
                throw new IllegalArgumentException("sampleRate is out of range");
            }
            if (channels < 1 || channels > 8) {
                throw new IllegalArgumentException("channels is out of range");
            }
            if (framesPerChunk < 64 || framesPerChunk > 4_096) {
                throw new IllegalArgumentException("framesPerChunk is out of range");
            }
        }

        public int bytesPerFrame() {
            return Math.multiplyExact(channels, Float.BYTES);
        }
    }

    record StartRequest(StreamKey key, long observedAtMs, AudioFormat format) {
        public StartRequest {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(format, "format");
            if (observedAtMs < 0) throw new IllegalArgumentException("observedAtMs must be non-negative");
        }
    }

    record AudioChunk(long sequence, byte[] pcm) {
        public AudioChunk {
            if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
            Objects.requireNonNull(pcm, "pcm");
            pcm = Arrays.copyOf(pcm, pcm.length);
        }

        @Override
        public byte[] pcm() {
            return Arrays.copyOf(pcm, pcm.length);
        }
    }
}
