package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.conversation.ConversationResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class UnrealStreamingSpeechObserverTest {
    @Test
    void dispatchesFirstCompleteSentenceBeforeConversationCompletes() {
        var speech = new CapturingSpeechPort();
        var observer = new UnrealStreamingSpeechObserver(
                "session-1", "unreal:g3:message-1", 3, speech, () -> true, 120);

        observer.onTextDelta("첫 문장입니다. 아직 둘째");

        assertThat(speech.requests).hasSize(1);
        assertThat(speech.requests.getFirst().text()).isEqualTo("첫 문장입니다.");
        observer.onTextDelta(" 문장");
        observer.onCompleted(new ConversationResponse(
                "run-1", "첫 문장입니다. 아직 둘째 문장", List.of(), Duration.ZERO));
        assertThat(speech.requests).extracting(UnrealSpeechPreparationRequest::text)
                .containsExactly("첫 문장입니다.", "아직 둘째 문장");
        assertThat(speech.requests).extracting(UnrealSpeechPreparationRequest::utteranceIndex)
                .containsExactly(0, 1);
        assertThat(speech.ends).singleElement().satisfies(end -> {
            assertThat(end.utteranceCount()).isEqualTo(2);
            assertThat(end.outcome()).isEqualTo("completed");
        });
    }

    @Test
    void stopsDispatchingWhenBargeInChangesGeneration() {
        var current = new AtomicBoolean(true);
        var requests = new ArrayList<UnrealSpeechPreparationRequest>();
        var observer = new UnrealStreamingSpeechObserver(
                "session-1", "unreal:g1:message-1", 1,
                (request, check) -> requests.add(request), current::get, 120);
        observer.onTextDelta("첫 문장입니다.");
        current.set(false);
        observer.onTextDelta("버려야 합니다.");
        observer.onCompleted(new ConversationResponse(
                "run-1", "첫 문장입니다. 버려야 합니다.", List.of(), Duration.ZERO));

        assertThat(requests).extracting(UnrealSpeechPreparationRequest::text)
                .containsExactly("첫 문장입니다.");
    }

    @Test
    void recordsFirstDeltaAndFirstSpeakableSentenceOnce() {
        var registry = new SimpleMeterRegistry();
        var metrics = new UnrealRuntimeMetrics(registry);
        var observer = new UnrealStreamingSpeechObserver(
                "session-1", "unreal:g2:message-1", 2,
                UnrealSpeechPreparationPort.NOOP, () -> true, 120,
                metrics, System.nanoTime());

        observer.onTextDelta("첫");
        observer.onTextDelta(" 문장입니다.");
        observer.onTextDelta("둘째 문장입니다.");

        assertThat(registry.timer("gahyeon.unreal.cognition.first.delta").count()).isEqualTo(1);
        assertThat(registry.timer("gahyeon.unreal.cognition.first.sentence").count()).isEqualTo(1);
    }

    @Test
    void endsAPartiallyPreparedSequenceAsFailedWithoutSpeakingTheFragment() {
        var speech = new CapturingSpeechPort();
        var observer = new UnrealStreamingSpeechObserver(
                "session-1", "unreal:g4:message-1", 4,
                speech, () -> true, 120);

        observer.onTextDelta("완성 문장. 미완성 조각");
        observer.onFailed(new IllegalStateException("provider disconnected"));

        assertThat(speech.requests).extracting(UnrealSpeechPreparationRequest::text)
                .containsExactly("완성 문장.");
        assertThat(speech.ends).singleElement().satisfies(end -> {
            assertThat(end.utteranceCount()).isEqualTo(1);
            assertThat(end.outcome()).isEqualTo("failed");
        });
    }

    @Test
    void boundsOneModelResponseToSixtyFourUtterancesAndEndsItAsFailed() {
        var speech = new CapturingSpeechPort();
        var observer = new UnrealStreamingSpeechObserver(
                "session-1", "unreal:g5:message-1", 5, speech, () -> true, 120);

        observer.onTextDelta("문장입니다. ".repeat(65));
        observer.onTextDelta("이후 문장도 무시합니다.");
        observer.onCompleted(new ConversationResponse(
                "run-1", "ignored", List.of(), Duration.ZERO));

        assertThat(speech.requests).hasSize(64);
        assertThat(speech.ends).singleElement().satisfies(end -> {
            assertThat(end.utteranceCount()).isEqualTo(64);
            assertThat(end.outcome()).isEqualTo("failed");
        });
    }

    private static final class CapturingSpeechPort implements UnrealSpeechPreparationPort {
        private final List<UnrealSpeechPreparationRequest> requests = new ArrayList<>();
        private final List<UnrealSpeechSequenceEndRequest> ends = new ArrayList<>();

        @Override
        public void prepare(
                UnrealSpeechPreparationRequest request,
                java.util.function.BooleanSupplier currentGeneration) {
            requests.add(request);
        }

        @Override
        public void finishSequence(
                UnrealSpeechSequenceEndRequest request,
                java.util.function.BooleanSupplier currentGeneration) {
            ends.add(request);
        }
    }
}
