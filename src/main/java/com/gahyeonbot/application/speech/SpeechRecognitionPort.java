package com.gahyeonbot.application.speech;

/** Provider-facing speech recognition capability. Implementations own API/model details. */
public interface SpeechRecognitionPort {
    boolean isReady();
    String transcribe(byte[] wavAudio);
}
