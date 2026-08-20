package com.gahyeonbot.application.speech;

import com.gahyeonbot.core.speech.AudioOutput;
import com.gahyeonbot.core.speech.ExpressiveSpeechRequest;
import com.gahyeonbot.core.speech.ExpressiveSpeechSynthesisUseCase;
import com.gahyeonbot.core.speech.VoiceProfileId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public final class DefaultExpressiveSpeechSynthesisService implements ExpressiveSpeechSynthesisUseCase {
    private final ObjectProvider<ExpressiveSpeechSynthesisPort> provider;

    public DefaultExpressiveSpeechSynthesisService(ObjectProvider<ExpressiveSpeechSynthesisPort> provider) {
        this.provider = provider;
    }

    @Override
    public boolean isExpressiveReady(VoiceProfileId voiceProfile) {
        ExpressiveSpeechSynthesisPort selected = provider.getIfAvailable();
        return selected != null && selected.isReady(voiceProfile);
    }

    @Override
    public AudioOutput synthesizeExpressive(ExpressiveSpeechRequest request) {
        if (request == null) throw new IllegalArgumentException("expressive speech request is required");
        ExpressiveSpeechSynthesisPort selected = provider.getIfAvailable();
        if (selected == null || !selected.isReady(request.voiceProfile())) {
            throw new IllegalStateException("expressive TTS is not ready");
        }
        return selected.synthesize(request);
    }
}
