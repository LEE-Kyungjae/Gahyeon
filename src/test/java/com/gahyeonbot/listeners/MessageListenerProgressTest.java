package com.gahyeonbot.listeners;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageListenerProgressTest {

    @Test
    void rendersACompactSpinnerWithElapsedTime() {
        assertThat(MessageListener.progressText(0, 0))
                .isEqualTo("⠋ 답변을 준비하고 있어요 · 0초");
        assertThat(MessageListener.progressText(1, 4))
                .isEqualTo("⠙ 답변을 준비하고 있어요 · 4초");
    }

    @Test
    void wrapsSpinnerFramesSafely() {
        assertThat(MessageListener.progressText(10, 40))
                .startsWith("⠋ ")
                .endsWith("40초");
    }
}
