package com.gahyeonbot.adapters.discord.voice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceAssistantTranscriptionGuardTest {

    @Test
    void rejectsKnownHallucinationFromShortAudio() {
        assertThat(VoiceAssistantService.isLikelyShortAudioHallucination(
                "감사합니다.", 1_340, 672))
                .isTrue();
    }

    @Test
    void keepsSamePhraseWhenAudioContainsAFullUtterance() {
        assertThat(VoiceAssistantService.isLikelyShortAudioHallucination(
                "감사합니다.", 3_000, 1_500))
                .isFalse();
    }

    @Test
    void keepsOrdinaryShortSpeech() {
        assertThat(VoiceAssistantService.isLikelyShortAudioHallucination(
                "안녕", 920, 624))
                .isFalse();
    }
}
