package com.gahyeonbot.application.speech;

import com.gahyeonbot.core.speech.*;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DefaultSpeechSynthesisService implements SpeechSynthesisUseCase {
    private final SpeechSynthesisPort synthesis;

    public DefaultSpeechSynthesisService(SpeechSynthesisPort synthesis) {
        this.synthesis = synthesis;
    }

    @Override
    public boolean isReady(VoiceProfileId voiceProfile) {
        return synthesis.isReady(voiceProfile);
    }

    @Override
    public List<SpeechSegment> prepare(String text) {
        return List.copyOf(synthesis.prepare(text));
    }

    @Override
    public AudioOutput synthesize(SpeechSegment segment, VoiceProfileId voiceProfile) {
        if (segment == null) throw new IllegalArgumentException("segment가 필요합니다.");
        if (voiceProfile == null) throw new IllegalArgumentException("voiceProfile이 필요합니다.");
        return synthesis.synthesize(segment, voiceProfile);
    }
}
