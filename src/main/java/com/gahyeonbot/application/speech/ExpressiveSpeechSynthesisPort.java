package com.gahyeonbot.application.speech;

import com.gahyeonbot.core.speech.AudioOutput;
import com.gahyeonbot.core.speech.ExpressiveSpeechRequest;
import com.gahyeonbot.core.speech.VoiceProfileId;

public interface ExpressiveSpeechSynthesisPort {
    boolean isReady(VoiceProfileId voiceProfile);
    AudioOutput synthesize(ExpressiveSpeechRequest request);
}
