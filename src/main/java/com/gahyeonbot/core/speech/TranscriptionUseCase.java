package com.gahyeonbot.core.speech;

public interface TranscriptionUseCase {
    boolean isReady();
    String transcribe(AudioInput audio);
}
