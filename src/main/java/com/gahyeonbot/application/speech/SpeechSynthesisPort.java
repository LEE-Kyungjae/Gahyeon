package com.gahyeonbot.application.speech;

import com.gahyeonbot.core.speech.AudioOutput;
import com.gahyeonbot.core.speech.SpeechSegment;
import com.gahyeonbot.core.speech.VoiceProfileId;

import java.util.List;

public interface SpeechSynthesisPort {
    boolean isReady(VoiceProfileId voiceProfile);
    List<SpeechSegment> prepare(String text);
    AudioOutput synthesize(SpeechSegment segment, VoiceProfileId voiceProfile);
}
