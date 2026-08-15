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

    @Test
    void rejectsImplausiblyLongTranscriptFromSubsecondAudio() {
        assertThat(VoiceAssistantService.isLikelyShortAudioHallucination(
                "아름다워드, 레포, 레포, 레포, 레포, 레포, 레포, 레포, 레포,",
                660,
                528))
                .isTrue();
    }

    @Test
    void rejectsDominantRepeatedTokenEvenFromLongAudio() {
        assertThat(VoiceAssistantService.isLikelyShortAudioHallucination(
                "에이전트, 에이전트, 에이전트, 에이전트, 에이전트, 에이전트",
                8_000,
                6_000))
                .isTrue();
    }

    @Test
    void keepsNaturalRepetitionInARealSentence() {
        assertThat(VoiceAssistantService.isLikelyShortAudioHallucination(
                "아니 아니, 그게 아니라 다른 레포를 찾아줘",
                3_200,
                2_400))
                .isFalse();
    }
}
