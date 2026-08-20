package com.gahyeonbot.core.speech;

public interface ExpressiveSpeechSynthesisUseCase {
    boolean isExpressiveReady(VoiceProfileId voiceProfile);
    AudioOutput synthesizeExpressive(ExpressiveSpeechRequest request);
}
