package com.gahyeonbot.adapters.speech;

import com.gahyeonbot.core.speech.SpeechSegment;
import com.gahyeonbot.core.speech.VoiceProfileId;
import com.gahyeonbot.services.assistant.AssistantProperties;
import com.gahyeonbot.services.tts.TtsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TtsServiceSynthesisAdapterTest {
    @TempDir Path tempDir;

    @Test
    void resolvesAssistantVoiceInternallyAndReturnsOwnedBytes() throws Exception {
        TtsService tts = mock(TtsService.class);
        AssistantProperties properties = new AssistantProperties();
        properties.setTtsProvider("voicebox");
        Path providerFile = tempDir.resolve("provider.wav");
        Files.write(providerFile, new byte[]{1, 2, 3});
        when(tts.isEnabled()).thenReturn(true);
        when(tts.prepareSegments("첫 문장. 둘째 문장."))
                .thenReturn(List.of("첫 문장.", "둘째 문장."));
        when(tts.synthesizeSegmentToAudio("첫 문장.", "voicebox"))
                .thenReturn(providerFile);
        var adapter = new TtsServiceSynthesisAdapter(tts, properties);

        var segments = adapter.prepare("첫 문장. 둘째 문장.");
        var audio = adapter.synthesize(segments.getFirst(), VoiceProfileId.ASSISTANT);

        assertThat(segments).containsExactly(
                new SpeechSegment(0, "첫 문장."),
                new SpeechSegment(1, "둘째 문장."));
        assertThat(audio.data()).containsExactly(1, 2, 3);
        assertThat(audio.mediaType()).isEqualTo("audio/wav");
        assertThat(Files.exists(providerFile)).isFalse();
        verify(tts).synthesizeSegmentToAudio("첫 문장.", "voicebox");
    }

    @Test
    void defaultVoiceUsesTheGlobalTtsSelection() throws Exception {
        TtsService tts = mock(TtsService.class);
        AssistantProperties properties = new AssistantProperties();
        Path providerFile = tempDir.resolve("provider.mp3");
        Files.write(providerFile, new byte[]{4, 5});
        when(tts.synthesizeSegmentToAudio("안녕")).thenReturn(providerFile);
        var adapter = new TtsServiceSynthesisAdapter(tts, properties);

        var audio = adapter.synthesize(
                new SpeechSegment(0, "안녕"), VoiceProfileId.DEFAULT);

        assertThat(audio.mediaType()).isEqualTo("audio/mpeg");
        assertThat(audio.fileExtension()).isEqualTo("mp3");
        verify(tts).synthesizeSegmentToAudio("안녕");
    }

    @Test
    void rejectsAndDeletesOversizedProviderAudioBeforeReadingIt() throws Exception {
        TtsService tts = mock(TtsService.class);
        AssistantProperties properties = new AssistantProperties();
        properties.setTtsProvider("voicebox");
        Path providerFile = tempDir.resolve("oversized.wav");
        try (var channel = Files.newByteChannel(
                providerFile,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.position(TtsServiceSynthesisAdapter.MAX_SYNTHESIZED_AUDIO_BYTES);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        when(tts.synthesizeSegmentToAudio("너무 긴 음성", "voicebox"))
                .thenReturn(providerFile);
        var adapter = new TtsServiceSynthesisAdapter(tts, properties);

        assertThatThrownBy(() -> adapter.synthesize(
                new SpeechSegment(0, "너무 긴 음성"), VoiceProfileId.ASSISTANT))
                .isInstanceOf(TtsServiceSynthesisAdapter.SpeechSynthesisException.class)
                .hasRootCauseMessage("합성 음성 크기가 허용 범위를 벗어났습니다: 16777217");
        assertThat(Files.exists(providerFile)).isFalse();
    }
}
