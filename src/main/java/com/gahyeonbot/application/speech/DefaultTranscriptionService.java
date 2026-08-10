package com.gahyeonbot.application.speech;

import com.gahyeonbot.core.speech.AudioInput;
import com.gahyeonbot.core.speech.TranscriptionUseCase;
import org.springframework.stereotype.Service;

@Service
public class DefaultTranscriptionService implements TranscriptionUseCase {
    private final SpeechRecognitionPort recognition;

    public DefaultTranscriptionService(SpeechRecognitionPort recognition) {
        this.recognition = recognition;
    }

    @Override
    public boolean isReady() {
        return recognition.isReady();
    }

    @Override
    public String transcribe(AudioInput audio) {
        if (audio == null) throw new IllegalArgumentException("audio가 필요합니다.");
        if (!"audio/wav".equalsIgnoreCase(audio.mediaType())) {
            throw new IllegalArgumentException("현재 audio/wav 입력만 지원합니다.");
        }
        String transcript = recognition.transcribe(audio.data());
        return transcript == null ? "" : transcript.trim();
    }
}
