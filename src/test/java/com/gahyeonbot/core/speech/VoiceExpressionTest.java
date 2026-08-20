package com.gahyeonbot.core.speech;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceExpressionTest {
    @Test
    void normalizesKnownStylesAndPreservesContinuousIntensity() {
        var expression = new VoiceExpression(" Fake_Cute ", 0.73, "playful refusal");
        assertThat(expression.style()).isEqualTo("fake_cute");
        assertThat(expression.intensity()).isEqualTo(0.73);
    }

    @Test
    void rejectsUnboundedOrInventedProviderInstructions() {
        assertThatThrownBy(() -> new VoiceExpression("arbitrary_prompt", 0.5, "talk"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VoiceExpression("natural", 1.1, "talk"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
