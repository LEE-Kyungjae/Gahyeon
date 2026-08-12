package com.gahyeonbot.adapters.unreal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IncrementalSentenceAccumulatorTest {
    @Test
    void reconstructsSentencesAcrossArbitraryTokenBoundaries() {
        var accumulator = new IncrementalSentenceAccumulator(120);

        assertThat(accumulator.accept("안녕하")).isEmpty();
        assertThat(accumulator.accept("세요. 다음 문")).containsExactly("안녕하세요.");
        assertThat(accumulator.accept("장입니다! 나머지")).containsExactly("다음 문장입니다!");
        assertThat(accumulator.finish()).containsExactly("나머지");
    }

    @Test
    void releasesLongUnpunctuatedTextAtASafeBoundary() {
        var accumulator = new IncrementalSentenceAccumulator(20);

        assertThat(accumulator.accept("문장 부호 없이 충분히 길어진 텍스트 덩어리 입니다 "))
                .isNotEmpty()
                .allMatch(value -> !value.isBlank());
    }

    @Test
    void hardSplitsLongTextWithoutAnySafeBoundary() {
        var accumulator = new IncrementalSentenceAccumulator(20);

        var emitted = accumulator.accept("가".repeat(125));

        assertThat(emitted).hasSize(3).allMatch(value -> value.length() == 40);
        assertThat(accumulator.finish()).containsExactly("가".repeat(5));
    }
}
