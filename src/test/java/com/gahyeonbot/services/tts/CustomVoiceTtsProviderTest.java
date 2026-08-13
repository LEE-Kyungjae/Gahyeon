package com.gahyeonbot.services.tts;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomVoiceTtsProviderTest {
    @Test
    void acceptsAnIdentityBoundApprovedRuntimeResponse() {
        TtsProperties.Custom expected = expected();
        HttpHeaders headers = headers("approved-voice", "a".repeat(64), "b".repeat(64));

        assertThatCode(() -> CustomVoiceTtsProvider.verifyResponseIdentity(headers, expected))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWrongAliasModelOrConfigDigestBeforePlayback() {
        TtsProperties.Custom expected = expected();

        assertThatThrownBy(() -> CustomVoiceTtsProvider.verifyResponseIdentity(
                headers("other-voice", "a".repeat(64), "b".repeat(64)), expected))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("X-Piper-Model");
        assertThatThrownBy(() -> CustomVoiceTtsProvider.verifyResponseIdentity(
                headers("approved-voice", "c".repeat(64), "b".repeat(64)), expected))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Model-SHA256");
        assertThatThrownBy(() -> CustomVoiceTtsProvider.verifyResponseIdentity(
                headers("approved-voice", "a".repeat(64), "c".repeat(64)), expected))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Config-SHA256");
    }

    @Test
    void digestPinningFailsClosedWhenRuntimeOmitsIdentityHeaders() {
        assertThatThrownBy(() -> CustomVoiceTtsProvider.verifyResponseIdentity(
                new HttpHeaders(), expected()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("X-Piper-Model-SHA256");
    }

    @Test
    void genericCustomServerRemainsCompatibleWithoutDigestPins() {
        TtsProperties.Custom expected = new TtsProperties.Custom();
        expected.setModel("generic-alias");

        assertThatCode(() -> CustomVoiceTtsProvider.verifyResponseIdentity(
                new HttpHeaders(), expected)).doesNotThrowAnyException();
    }

    @Test
    void acceptsValidWavAndRejectsWrongMediaTypeOrGarbage() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.valueOf("audio/wav"));
        byte[] wav = wav();
        assertThatCode(() -> CustomVoiceTtsProvider.verifyAudio(headers, "wav", wav))
                .doesNotThrowAnyException();

        headers.setContentType(MediaType.valueOf("audio/mpeg"));
        assertThatThrownBy(() -> CustomVoiceTtsProvider.verifyAudio(headers, "wav", wav))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Content-Type");
        headers.setContentType(MediaType.valueOf("audio/wav"));
        assertThatThrownBy(() -> CustomVoiceTtsProvider.verifyAudio(
                headers, "wav", new byte[512]))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("유효한 WAV");
    }

    private static TtsProperties.Custom expected() {
        TtsProperties.Custom expected = new TtsProperties.Custom();
        expected.setModel("approved-voice");
        expected.setModelSha256("a".repeat(64));
        expected.setConfigSha256("b".repeat(64));
        return expected;
    }

    private static HttpHeaders headers(String alias, String modelSha, String configSha) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Piper-Model", alias);
        headers.set("X-Piper-Model-SHA256", modelSha);
        headers.set("X-Piper-Config-SHA256", configSha);
        return headers;
    }

    private static byte[] wav() {
        AudioFormat format = new AudioFormat(16_000, 16, 1, true, false);
        byte[] pcm = new byte[3_200];
        try (var input = new AudioInputStream(
                new java.io.ByteArrayInputStream(pcm), format, pcm.length / 2);
             var output = new ByteArrayOutputStream()) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
