package com.gahyeonbot.application.speech;

import com.gahyeonbot.core.speech.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultExpressiveSpeechSynthesisServiceTest {
    @Test
    void failsClosedInsteadOfSilentlyDroppingExpression() {
        var beans = new DefaultListableBeanFactory();
        var service = new DefaultExpressiveSpeechSynthesisService(
                beans.getBeanProvider(ExpressiveSpeechSynthesisPort.class));

        assertThat(service.isExpressiveReady(VoiceProfileId.ASSISTANT)).isFalse();
        assertThatThrownBy(() -> service.synthesizeExpressive(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not ready");
    }

    @Test
    void forwardsTheExactVoiceIdentityAndExpression() {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("expressive", new ExpressiveSpeechSynthesisPort() {
            @Override public boolean isReady(VoiceProfileId voiceProfile) { return true; }
            @Override public AudioOutput synthesize(ExpressiveSpeechRequest request) {
                assertThat(request.voiceProfile()).isEqualTo(VoiceProfileId.ASSISTANT);
                assertThat(request.expression().style()).isEqualTo("fake_cute");
                return new AudioOutput(new byte[] { 1 }, "audio/wav", "wav");
            }
        });
        var service = new DefaultExpressiveSpeechSynthesisService(
                beans.getBeanProvider(ExpressiveSpeechSynthesisPort.class));

        assertThat(service.synthesizeExpressive(request()).data()).containsExactly(1);
    }

    private static ExpressiveSpeechRequest request() {
        return new ExpressiveSpeechRequest(
                new SpeechSegment(0, "싫어어~"),
                VoiceProfileId.ASSISTANT,
                new VoiceExpression("fake_cute", 0.75, "playful_refusal"));
    }
}
