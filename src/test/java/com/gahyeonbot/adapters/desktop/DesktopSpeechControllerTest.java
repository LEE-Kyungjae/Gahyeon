package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.core.speech.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DesktopSpeechControllerTest {
    @Test
    void delegatesWavTranscriptionThroughCoreUseCase() {
        TranscriptionUseCase transcription = mock(TranscriptionUseCase.class);
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        ExpressiveSpeechSynthesisUseCase expressive = mock(ExpressiveSpeechSynthesisUseCase.class);
        when(transcription.isReady()).thenReturn(true);
        when(transcription.transcribe(any())).thenReturn("안녕하세요");
        var controller = new DesktopSpeechController(transcription, synthesis, expressive);

        var result = controller.transcribe(new byte[] { 1, 2, 3 });

        assertThat(result.transcript()).isEqualTo("안녕하세요");
        verify(transcription).transcribe(argThat(input ->
                input.mediaType().equals("audio/wav") && input.data().length == 3));
    }

    @Test
    void returnsProviderAudioWithoutCreatingDesktopFiles() {
        TranscriptionUseCase transcription = mock(TranscriptionUseCase.class);
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        ExpressiveSpeechSynthesisUseCase expressive = mock(ExpressiveSpeechSynthesisUseCase.class);
        when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
        when(synthesis.synthesize(
                new SpeechSegment(0, "반가워요"), VoiceProfileId.ASSISTANT))
                .thenReturn(new AudioOutput(new byte[] { 4, 5, 6 }, "audio/wav", "wav"));
        var controller = new DesktopSpeechController(transcription, synthesis, expressive);

        var response = controller.synthesize(new DesktopSpeechController.SynthesizeSpeechRequest(
                0, "반가워요", VoiceProfileId.ASSISTANT.value()));

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("audio/wav"));
        assertThat(response.getBody()).containsExactly(4, 5, 6);
    }

    @Test
    void preservesExpressionControlsForAnExpressiveProvider() {
        TranscriptionUseCase transcription = mock(TranscriptionUseCase.class);
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        ExpressiveSpeechSynthesisUseCase expressive = mock(ExpressiveSpeechSynthesisUseCase.class);
        when(expressive.isExpressiveReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
        when(expressive.synthesizeExpressive(any())).thenReturn(
                new AudioOutput(new byte[] { 7, 8 }, "audio/wav", "wav"));
        var controller = new DesktopSpeechController(transcription, synthesis, expressive);

        var response = controller.synthesize(new DesktopSpeechController.SynthesizeSpeechRequest(
                0, "정말 온 거야?", VoiceProfileId.ASSISTANT.value(),
                new DesktopSpeechController.VoiceExpressionRequest(
                        "surprised", 0.8, "reunion")));

        assertThat(response.getBody()).containsExactly(7, 8);
        verify(expressive).synthesizeExpressive(argThat(request ->
                request.expression().style().equals("surprised")
                        && request.expression().intensity() == 0.8
                        && request.expression().communicativeIntent().equals("reunion")));
        verify(synthesis, never()).synthesize(any(), any());
    }
}
