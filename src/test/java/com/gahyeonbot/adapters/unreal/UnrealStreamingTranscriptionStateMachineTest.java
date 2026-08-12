package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.speech.StreamingTranscriptionPort;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnrealStreamingTranscriptionStateMachineTest {
    private static final StreamingTranscriptionPort.StreamKey KEY =
            new StreamingTranscriptionPort.StreamKey("session-1", "stream-1", 7);
    private static final StreamingTranscriptionPort.AudioFormat FORMAT =
            new StreamingTranscriptionPort.AudioFormat("float32le", 16_000, 1, 320);

    @Test
    void acceptsOrderedAudioAndOneFinalAfterEnd() {
        FakeProvider provider = new FakeProvider();
        RecordingSink sink = new RecordingSink();
        var machine = new UnrealStreamingTranscriptionStateMachine(provider, sink);

        machine.start(new StreamingTranscriptionPort.StartRequest(KEY, 10, FORMAT));
        machine.acceptBinary(frame(0, 320 * Float.BYTES));
        provider.listener.onPartial(0, "안녕", 0.8);
        machine.acceptBinary(frame(1, 320 * Float.BYTES));
        machine.end(KEY, 1);
        provider.listener.onFinal(1, "안녕하세요", "ko-KR");
        provider.listener.onFinal(2, "두 번", "ko-KR");

        assertThat(provider.offered).containsExactly(0L, 1L);
        assertThat(provider.finished).isTrue();
        assertThat(sink.events).containsExactly(
                "partial:0:안녕", "final:1:안녕하세요:ko-KR");
        assertThat(sink.errors).isEmpty();
    }

    @Test
    void sequenceGapCancelsWholeUtterance() {
        FakeProvider provider = new FakeProvider();
        RecordingSink sink = new RecordingSink();
        var machine = new UnrealStreamingTranscriptionStateMachine(provider, sink);
        machine.start(new StreamingTranscriptionPort.StartRequest(KEY, 10, FORMAT));

        machine.acceptBinary(frame(1, 320 * Float.BYTES));

        assertThat(provider.cancelled).isEqualTo(
                StreamingTranscriptionPort.CancelReason.CAPTURE_ERROR);
        assertThat(sink.errors).containsExactly("stream-1:sequence_gap:false");
        assertThat(provider.offered).isEmpty();
    }

    @Test
    void malformedOrOversizedPcmNeverReachesProvider() {
        FakeProvider provider = new FakeProvider();
        RecordingSink sink = new RecordingSink();
        var machine = new UnrealStreamingTranscriptionStateMachine(provider, sink);
        machine.start(new StreamingTranscriptionPort.StartRequest(KEY, 10, FORMAT));

        machine.acceptBinary(frame(0, 3));

        assertThat(sink.errors).containsExactly("stream-1:format_changed:false");
        assertThat(provider.offered).isEmpty();
    }

    @Test
    void backpressureIsFailureRatherThanSilentAudioLoss() {
        FakeProvider provider = new FakeProvider();
        provider.offerResult = StreamingTranscriptionPort.OfferResult.BACKPRESSURE;
        RecordingSink sink = new RecordingSink();
        var machine = new UnrealStreamingTranscriptionStateMachine(provider, sink);
        machine.start(new StreamingTranscriptionPort.StartRequest(KEY, 10, FORMAT));

        machine.acceptBinary(frame(0, 320 * Float.BYTES));

        assertThat(provider.cancelled).isEqualTo(
                StreamingTranscriptionPort.CancelReason.BACKPRESSURE);
        assertThat(sink.errors).containsExactly("stream-1:backpressure:true");
    }

    @Test
    void hardDeadlineCancelsProviderAndFencesLateResults() {
        FakeProvider provider = new FakeProvider();
        RecordingSink sink = new RecordingSink();
        var machine = new UnrealStreamingTranscriptionStateMachine(provider, sink);
        machine.start(new StreamingTranscriptionPort.StartRequest(KEY, 10, FORMAT));

        machine.timeout(KEY);
        provider.listener.onPartial(0, "늦은 결과", 0.9);

        assertThat(provider.cancelled).isEqualTo(StreamingTranscriptionPort.CancelReason.TIMEOUT);
        assertThat(sink.errors).containsExactly("stream-1:provider_timeout:true");
        assertThat(sink.events).isEmpty();
    }

    @Test
    void newerGenerationPreemptsOldButSameGenerationIsRejected() {
        FakeProvider provider = new FakeProvider();
        RecordingSink sink = new RecordingSink();
        var machine = new UnrealStreamingTranscriptionStateMachine(provider, sink);
        machine.start(new StreamingTranscriptionPort.StartRequest(KEY, 10, FORMAT));
        var duplicate = new StreamingTranscriptionPort.StreamKey("session-1", "duplicate", 7);
        machine.start(new StreamingTranscriptionPort.StartRequest(duplicate, 11, FORMAT));

        assertThat(sink.errors).containsExactly("duplicate:invalid_lifecycle:false");
        assertThat(provider.cancelled).isNull();

        var newer = new StreamingTranscriptionPort.StreamKey("session-1", "stream-2", 8);
        machine.start(new StreamingTranscriptionPort.StartRequest(newer, 12, FORMAT));
        assertThat(provider.cancelled).isEqualTo(StreamingTranscriptionPort.CancelReason.BARGE_IN);
        assertThat(provider.openCount).isEqualTo(2);
    }

    @Test
    void finalBeforeEndOrOutOfOrderProviderResultFailsClosed() {
        FakeProvider provider = new FakeProvider();
        RecordingSink sink = new RecordingSink();
        var machine = new UnrealStreamingTranscriptionStateMachine(provider, sink);
        machine.start(new StreamingTranscriptionPort.StartRequest(KEY, 10, FORMAT));

        provider.listener.onFinal(0, "too soon", "ko-KR");

        assertThat(sink.errors).containsExactly("stream-1:provider_error:true");
        assertThat(provider.cancelled).isEqualTo(
                StreamingTranscriptionPort.CancelReason.CLIENT_RESET);
    }

    @Test
    void unavailableProviderDoesNotOpenSession() {
        FakeProvider provider = new FakeProvider();
        provider.ready = false;
        RecordingSink sink = new RecordingSink();
        var machine = new UnrealStreamingTranscriptionStateMachine(provider, sink);

        machine.start(new StreamingTranscriptionPort.StartRequest(KEY, 10, FORMAT));

        assertThat(provider.openCount).isZero();
        assertThat(sink.errors).containsExactly("stream-1:provider_unavailable:true");
    }

    private static byte[] frame(long sequence, int pcmBytes) {
        return ByteBuffer.allocate(Long.BYTES + pcmBytes)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(sequence)
                .array();
    }

    private static final class FakeProvider implements StreamingTranscriptionPort {
        private boolean ready = true;
        private int openCount;
        private ResultListener listener;
        private final List<Long> offered = new ArrayList<>();
        private OfferResult offerResult = OfferResult.ACCEPTED;
        private boolean finished;
        private CancelReason cancelled;

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public Session open(StartRequest request, ResultListener listener) {
            openCount++;
            this.listener = listener;
            return new Session() {
                @Override
                public OfferResult offer(AudioChunk chunk) {
                    offered.add(chunk.sequence());
                    return offerResult;
                }

                @Override
                public void finish() {
                    finished = true;
                }

                @Override
                public void cancel(CancelReason reason) {
                    cancelled = reason;
                }
            };
        }
    }

    private static final class RecordingSink
            implements UnrealStreamingTranscriptionStateMachine.EventSink {
        private final List<String> events = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        @Override
        public boolean partial(StreamingTranscriptionPort.StreamKey key, long sequence,
                               String text, double stability) {
            events.add("partial:" + sequence + ":" + text);
            return true;
        }

        @Override
        public boolean completed(StreamingTranscriptionPort.StreamKey key, long sequence,
                                 String text, String language) {
            events.add("final:" + sequence + ":" + text + ":" + language);
            return true;
        }

        @Override
        public void error(StreamingTranscriptionPort.StreamKey key,
                          UnrealStreamingTranscriptionStateMachine.ErrorCode code,
                          boolean recoverable) {
            errors.add(key.streamId() + ":" + code.wireValue() + ":" + recoverable);
        }
    }
}
