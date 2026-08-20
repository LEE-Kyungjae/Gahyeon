package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.speech.VoiceExpression;
import com.gahyeonbot.core.speech.VoiceProfileId;

public record UnrealSpeechPreparationRequest(
        String sessionId,
        String correlationId,
        long generation,
        int utteranceIndex,
        String text,
        VoiceProfileId voiceProfile,
        VoiceExpression expression
) {
    public UnrealSpeechPreparationRequest(
            String sessionId,
            String correlationId,
            long generation,
            int utteranceIndex,
            String text) {
        this(sessionId, correlationId, generation, utteranceIndex, text,
                VoiceProfileId.ASSISTANT, null);
    }

    public UnrealSpeechPreparationRequest(
            String sessionId,
            String correlationId,
            long generation,
            String text) {
        this(sessionId, correlationId, generation, 0, text,
                VoiceProfileId.ASSISTANT, null);
    }

    public UnrealSpeechPreparationRequest {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (correlationId == null || correlationId.isBlank()) throw new IllegalArgumentException("correlationId is required");
        if (generation < 0) throw new IllegalArgumentException("generation must be non-negative");
        if (utteranceIndex < 0) throw new IllegalArgumentException("utteranceIndex must be non-negative");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text is required");
        if (voiceProfile == null) throw new IllegalArgumentException("voiceProfile is required");
    }
}
