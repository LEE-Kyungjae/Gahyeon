package com.gahyeonbot.core.speech;

import java.util.List;

public interface SpeechSynthesisUseCase {
    boolean isReady(VoiceProfileId voiceProfile);
    List<SpeechSegment> prepare(String text);
    AudioOutput synthesize(SpeechSegment segment, VoiceProfileId voiceProfile);
}
