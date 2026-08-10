package com.gahyeonbot.application.speech;

import com.gahyeonbot.core.speech.AudioInput;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultTranscriptionServiceTest {
    @Test
    void transcribesDefensivelyCopiedWavWithoutPlatformTypes() {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        SpeechRecognitionPort port = new SpeechRecognitionPort() {
            @Override public boolean isReady() { return true; }
            @Override public String transcribe(byte[] wavAudio) {
                captured.set(wavAudio);
                return "  안녕하세요  ";
            }
        };
        var service = new DefaultTranscriptionService(port);
        byte[] source = {1, 2, 3};
        AudioInput input = new AudioInput(source, "audio/wav");
        source[0] = 9;

        assertThat(service.transcribe(input)).isEqualTo("안녕하세요");
        assertThat(captured.get()).containsExactly(1, 2, 3);
    }

    @Test
    void rejectsUnsupportedAudioAtTheCoreBoundary() {
        SpeechRecognitionPort port = new SpeechRecognitionPort() {
            @Override public boolean isReady() { return true; }
            @Override public String transcribe(byte[] wavAudio) { return "unused"; }
        };
        var service = new DefaultTranscriptionService(port);

        assertThatThrownBy(() -> service.transcribe(new AudioInput(new byte[]{1}, "audio/mp3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audio/wav");
    }
}
