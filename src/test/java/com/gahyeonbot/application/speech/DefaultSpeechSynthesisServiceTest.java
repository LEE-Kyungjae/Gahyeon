package com.gahyeonbot.application.speech;

import com.gahyeonbot.core.speech.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSpeechSynthesisServiceTest {
    @Test
    void exposesLogicalVoiceAndBinaryAudioWithoutProviderOrPathTypes() {
        SpeechSynthesisPort port = new SpeechSynthesisPort() {
            @Override public boolean isReady(VoiceProfileId voiceProfile) { return true; }
            @Override public List<SpeechSegment> prepare(String text) {
                return List.of(new SpeechSegment(0, text));
            }
            @Override public AudioOutput synthesize(SpeechSegment segment, VoiceProfileId voiceProfile) {
                return new AudioOutput(new byte[]{1}, "audio/wav", "wav");
            }
        };
        var service = new DefaultSpeechSynthesisService(port);

        assertThat(service.isReady(VoiceProfileId.ASSISTANT)).isTrue();
        assertThat(service.prepare("안녕하세요")).containsExactly(new SpeechSegment(0, "안녕하세요"));
        assertThat(service.synthesize(
                new SpeechSegment(0, "안녕하세요"), VoiceProfileId.ASSISTANT).data())
                .containsExactly(1);
    }
}
