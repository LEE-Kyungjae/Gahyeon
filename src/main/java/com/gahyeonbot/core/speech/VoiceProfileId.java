package com.gahyeonbot.core.speech;

public record VoiceProfileId(String value) {
    public static final VoiceProfileId DEFAULT = new VoiceProfileId("gahyeon.default");
    public static final VoiceProfileId ASSISTANT = new VoiceProfileId("gahyeon.assistant");

    public VoiceProfileId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("voiceProfile이 필요합니다.");
        value = value.trim();
        if (value.length() > 100) throw new IllegalArgumentException("voiceProfile이 너무 깁니다.");
    }
}
