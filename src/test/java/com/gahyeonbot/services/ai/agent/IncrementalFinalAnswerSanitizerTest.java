package com.gahyeonbot.services.ai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IncrementalFinalAnswerSanitizerTest {
    @Test
    void suppressesAThinkBlockSplitAcrossProviderChunks() {
        var sanitizer = new IncrementalFinalAnswerSanitizer();

        assertThat(sanitizer.accept(" <thi")).isEmpty();
        assertThat(sanitizer.accept("nk>내부 추론")).isEmpty();
        assertThat(sanitizer.accept("입니다.</think> 최종 ")).isEqualTo("최종 ");
        assertThat(sanitizer.accept("답변입니다.")).isEqualTo("답변입니다.");
    }

    @Test
    void releasesOrdinaryTextAfterOnlyTheSmallPrefixGuard() {
        var sanitizer = new IncrementalFinalAnswerSanitizer();

        assertThat(sanitizer.accept("안녕")).isEqualTo("안녕");
        assertThat(sanitizer.accept("하세요.")).isEqualTo("하세요.");
    }
}
