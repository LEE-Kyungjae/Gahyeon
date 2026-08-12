package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.speech.StreamingTranscriptionPort;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/** Enforces the provider-independent Streaming STT v1 lifecycle for one transport connection. */
public final class UnrealStreamingTranscriptionStateMachine implements AutoCloseable {
    public static final int SEQUENCE_HEADER_BYTES = Long.BYTES;
    public static final int MAX_PCM_BYTES = 131_072;

    private final StreamingTranscriptionPort provider;
    private final EventSink sink;
    private ActiveStream active;

    public UnrealStreamingTranscriptionStateMachine(
            StreamingTranscriptionPort provider,
            EventSink sink) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public synchronized boolean start(StreamingTranscriptionPort.StartRequest request) {
        Objects.requireNonNull(request, "request");
        if (!provider.isReady()) {
            sink.error(request.key(), ErrorCode.PROVIDER_UNAVAILABLE, true);
            return false;
        }
        if (active != null) {
            if (request.key().generation() <= active.key.generation()) {
                sink.error(request.key(), ErrorCode.INVALID_LIFECYCLE, false);
                return false;
            }
            terminate(active, StreamingTranscriptionPort.CancelReason.BARGE_IN);
        }

        ActiveStream candidate = new ActiveStream(request.key(), request.format());
        active = candidate;
        if (!sink.started(candidate.key)) {
            fail(candidate, ErrorCode.INVALID_LIFECYCLE, false,
                    StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
            return false;
        }
        try {
            StreamingTranscriptionPort.Session opened = provider.open(
                    request, new ProviderResults(candidate));
            candidate.providerSession = opened;
            if (opened == null) {
                fail(candidate, ErrorCode.PROVIDER_UNAVAILABLE, true,
                        StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
            } else if (candidate.terminal) {
                safeCancel(candidate, StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
            }
        } catch (RuntimeException error) {
            fail(candidate, ErrorCode.PROVIDER_UNAVAILABLE, true,
                    StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
        }
        return active == candidate && !candidate.terminal;
    }

    public synchronized void acceptBinary(byte[] frame) {
        ActiveStream stream = requireActive(null);
        if (stream == null) {
            sink.connectionError(ErrorCode.INVALID_LIFECYCLE);
            return;
        }
        if (frame == null || frame.length <= SEQUENCE_HEADER_BYTES) {
            fail(stream, ErrorCode.FORMAT_CHANGED, false,
                    StreamingTranscriptionPort.CancelReason.CAPTURE_ERROR);
            return;
        }
        long sequence = ByteBuffer.wrap(frame, 0, SEQUENCE_HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN).getLong();
        if (sequence < 0 || sequence != stream.nextAudioSequence) {
            fail(stream, ErrorCode.SEQUENCE_GAP, false,
                    StreamingTranscriptionPort.CancelReason.CAPTURE_ERROR);
            return;
        }
        byte[] pcm = Arrays.copyOfRange(frame, SEQUENCE_HEADER_BYTES, frame.length);
        if (pcm.length > MAX_PCM_BYTES || pcm.length % stream.format.bytesPerFrame() != 0) {
            fail(stream, ErrorCode.FORMAT_CHANGED, false,
                    StreamingTranscriptionPort.CancelReason.CAPTURE_ERROR);
            return;
        }
        StreamingTranscriptionPort.OfferResult result;
        try {
            result = stream.providerSession.offer(
                    new StreamingTranscriptionPort.AudioChunk(sequence, pcm));
        } catch (RuntimeException error) {
            fail(stream, ErrorCode.PROVIDER_ERROR, true,
                    StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
            return;
        }
        if (result != StreamingTranscriptionPort.OfferResult.ACCEPTED) {
            fail(stream, ErrorCode.BACKPRESSURE, true,
                    StreamingTranscriptionPort.CancelReason.BACKPRESSURE);
            return;
        }
        stream.lastAudioSequence = sequence;
        stream.nextAudioSequence++;
    }

    public synchronized boolean end(StreamingTranscriptionPort.StreamKey key, long lastAudioSequence) {
        ActiveStream stream = requireActive(key);
        if (stream == null) return false;
        if (stream.ending || stream.lastAudioSequence < 0
                || lastAudioSequence != stream.lastAudioSequence) {
            fail(stream, ErrorCode.INVALID_LIFECYCLE, false,
                    StreamingTranscriptionPort.CancelReason.CAPTURE_ERROR);
            return false;
        }
        stream.ending = true;
        if (!sink.ended(stream.key)) {
            fail(stream, ErrorCode.INVALID_LIFECYCLE, false,
                    StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
            return false;
        }
        try {
            stream.providerSession.finish();
        } catch (RuntimeException error) {
            fail(stream, ErrorCode.PROVIDER_ERROR, true,
                    StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
        }
        return active == stream && !stream.terminal;
    }

    public synchronized void cancel(
            StreamingTranscriptionPort.StreamKey key,
            StreamingTranscriptionPort.CancelReason reason) {
        ActiveStream stream = requireActive(key);
        if (stream != null) terminate(stream, Objects.requireNonNull(reason, "reason"));
    }

    /** Terminates one exact utterance when its transport-owned hard deadline expires. */
    public synchronized void timeout(StreamingTranscriptionPort.StreamKey key) {
        ActiveStream stream = requireActive(key);
        if (stream != null) {
            fail(stream, ErrorCode.PROVIDER_TIMEOUT, true,
                    StreamingTranscriptionPort.CancelReason.TIMEOUT);
        }
    }

    public synchronized boolean isActive(StreamingTranscriptionPort.StreamKey key) {
        return active != null && active.key.equals(key) && !active.terminal;
    }

    @Override
    public synchronized void close() {
        if (active != null) terminate(active, StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
    }

    private ActiveStream requireActive(StreamingTranscriptionPort.StreamKey key) {
        if (active == null) {
            if (key != null) sink.error(key, ErrorCode.INVALID_LIFECYCLE, false);
            return null;
        }
        if (key != null && !active.key.equals(key)) {
            sink.error(key, ErrorCode.INVALID_LIFECYCLE, false);
            return null;
        }
        return active;
    }

    private void fail(
            ActiveStream stream,
            ErrorCode code,
            boolean recoverable,
            StreamingTranscriptionPort.CancelReason reason) {
        if (stream == null || stream.terminal) return;
        stream.terminal = true;
        if (active == stream) active = null;
        safeCancel(stream, reason);
        sink.error(stream.key, code, recoverable);
    }

    private void terminate(ActiveStream stream, StreamingTranscriptionPort.CancelReason reason) {
        if (stream == null || stream.terminal) return;
        stream.terminal = true;
        if (active == stream) active = null;
        safeCancel(stream, reason);
        sink.cancelled(stream.key, reason);
    }

    private void safeCancel(ActiveStream stream, StreamingTranscriptionPort.CancelReason reason) {
        if (stream.providerSession == null) return;
        try {
            stream.providerSession.cancel(reason);
        } catch (RuntimeException ignored) {
            // The application lifecycle is already terminal; provider cleanup is best effort.
        }
    }

    public interface EventSink {
        default boolean started(StreamingTranscriptionPort.StreamKey key) {
            return true;
        }

        boolean partial(StreamingTranscriptionPort.StreamKey key, long sequence, String text, double stability);

        default boolean ended(StreamingTranscriptionPort.StreamKey key) {
            return true;
        }

        boolean completed(StreamingTranscriptionPort.StreamKey key, long sequence, String text, String language);

        void error(StreamingTranscriptionPort.StreamKey key, ErrorCode code, boolean recoverable);

        default void cancelled(StreamingTranscriptionPort.StreamKey key,
                               StreamingTranscriptionPort.CancelReason reason) {
        }

        /** A binary frame has no identity fields, so an unbound frame is a connection-level error. */
        default void connectionError(ErrorCode code) {
        }
    }

    public enum ErrorCode {
        INVALID_LIFECYCLE("invalid_lifecycle"),
        SEQUENCE_GAP("sequence_gap"),
        FORMAT_CHANGED("format_changed"),
        BACKPRESSURE("backpressure"),
        PROVIDER_UNAVAILABLE("provider_unavailable"),
        PROVIDER_TIMEOUT("provider_timeout"),
        PROVIDER_ERROR("provider_error");

        private final String wireValue;

        ErrorCode(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    private final class ProviderResults implements StreamingTranscriptionPort.ResultListener {
        private final ActiveStream owner;

        private ProviderResults(ActiveStream owner) {
            this.owner = owner;
        }

        @Override
        public void onPartial(long resultSequence, String text, double stability) {
            synchronized (UnrealStreamingTranscriptionStateMachine.this) {
                if (!acceptResult(owner, resultSequence) || text == null || text.isBlank()
                        || text.length() > 8_192 || !Double.isFinite(stability)
                        || stability < 0 || stability > 1) {
                    if (active == owner) fail(owner, ErrorCode.PROVIDER_ERROR, true,
                            StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
                    return;
                }
                if (!sink.partial(owner.key, resultSequence, text.trim(), stability)) {
                    fail(owner, ErrorCode.INVALID_LIFECYCLE, false,
                            StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
                }
            }
        }

        @Override
        public void onFinal(long resultSequence, String text, String language) {
            synchronized (UnrealStreamingTranscriptionStateMachine.this) {
                if (!owner.ending || !acceptResult(owner, resultSequence)
                        || text == null || text.isBlank() || text.length() > 8_192
                        || language == null || !language.matches("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")) {
                    if (active == owner) fail(owner, ErrorCode.PROVIDER_ERROR, true,
                            StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
                    return;
                }
                if (!sink.completed(owner.key, resultSequence, text.trim(), language)) {
                    fail(owner, ErrorCode.INVALID_LIFECYCLE, false,
                            StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
                    return;
                }
                owner.terminal = true;
                if (active == owner) active = null;
            }
        }

        @Override
        public void onError(StreamingTranscriptionPort.ErrorCode code, boolean recoverable) {
            synchronized (UnrealStreamingTranscriptionStateMachine.this) {
                ErrorCode mapped = switch (Objects.requireNonNull(code, "code")) {
                    case PROVIDER_UNAVAILABLE -> ErrorCode.PROVIDER_UNAVAILABLE;
                    case PROVIDER_TIMEOUT -> ErrorCode.PROVIDER_TIMEOUT;
                    case PROVIDER_ERROR -> ErrorCode.PROVIDER_ERROR;
                };
                fail(owner, mapped, recoverable,
                        StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
            }
        }

        private boolean acceptResult(ActiveStream stream, long sequence) {
            if (active != stream || stream.terminal || sequence != stream.nextResultSequence) return false;
            stream.nextResultSequence++;
            return true;
        }
    }

    private static final class ActiveStream {
        private final StreamingTranscriptionPort.StreamKey key;
        private final StreamingTranscriptionPort.AudioFormat format;
        private StreamingTranscriptionPort.Session providerSession;
        private long nextAudioSequence;
        private long lastAudioSequence = -1;
        private long nextResultSequence;
        private boolean ending;
        private boolean terminal;

        private ActiveStream(
                StreamingTranscriptionPort.StreamKey key,
                StreamingTranscriptionPort.AudioFormat format) {
            this.key = key;
            this.format = format;
        }
    }
}
