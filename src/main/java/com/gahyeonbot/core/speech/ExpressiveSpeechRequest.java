package com.gahyeonbot.core.speech;

public record ExpressiveSpeechRequest(
        SpeechSegment segment,
        VoiceProfileId voiceProfile,
        VoiceExpression expression
) {
    public ExpressiveSpeechRequest {
        if (segment == null) throw new IllegalArgumentException("segment is required");
        if (voiceProfile == null) throw new IllegalArgumentException("voiceProfile is required");
        if (expression == null) throw new IllegalArgumentException("expression is required");
    }
}
