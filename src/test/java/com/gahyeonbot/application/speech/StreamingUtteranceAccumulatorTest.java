package com.gahyeonbot.application.speech;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingUtteranceAccumulatorTest {
    private static UtteranceSegmentationPolicy policy() {
        return new UtteranceSegmentationPolicy(1_000, 2, 3, 200, 100, 500, 500, 2, 1_000);
    }

    @Test
    void retainsPreRollAndCompletesAfterSilence() {
        FakeDetector detector = new FakeDetector(false, true, false);
        var accumulator = new StreamingUtteranceAccumulator(policy(), detector, 0);

        accumulator.accept(new byte[]{1, 2}, 0);
        accumulator.accept(new byte[]{3, 4}, 10);
        accumulator.accept(new byte[]{5, 6}, 20);

        assertThat(accumulator.poll(400)).isEmpty();
        var completed = accumulator.poll(520).orElseThrow();
        assertThat(completed.pcm()).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(completed.detectedSpeechMillis()).isEqualTo(100);
    }

    @Test
    void waitsLongerForShortSpeech() {
        FakeDetector detector = new FakeDetector(true);
        var accumulator = new StreamingUtteranceAccumulator(policy(), detector, 0);
        accumulator.accept(new byte[]{1, 2, 3}, 10);

        assertThat(accumulator.poll(300)).isEmpty();
        assertThat(accumulator.poll(510)).isPresent();
    }

    @Test
    void finalizesAtMaximumLengthWithoutSilence() {
        var accumulator = new StreamingUtteranceAccumulator(policy(), null, 0);
        accumulator.accept(new byte[3_000], 10);

        var completed = accumulator.poll(10).orElseThrow();
        assertThat(completed.pcm()).hasSize(2_000);
        assertThat(completed.capturedAudioMillis()).isEqualTo(2_000);
    }

    private static final class FakeDetector implements VoiceActivityDetector {
        private final Queue<Boolean> results = new ArrayDeque<>();

        private FakeDetector(Boolean... results) {
            this.results.addAll(java.util.List.of(results));
        }

        @Override
        public Detection detect(byte[] pcm) {
            boolean voice = results.remove();
            return new Detection(voice, voice ? 100 : 0);
        }
    }
}
