package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.core.speech.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/gahyeon/desktop/speech")
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
public class DesktopSpeechController {
    private static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024;

    private final TranscriptionUseCase transcription;
    private final SpeechSynthesisUseCase synthesis;
    private final ExpressiveSpeechSynthesisUseCase expressiveSynthesis;

    public DesktopSpeechController(
            TranscriptionUseCase transcription,
            SpeechSynthesisUseCase synthesis,
            ExpressiveSpeechSynthesisUseCase expressiveSynthesis) {
        this.transcription = transcription;
        this.synthesis = synthesis;
        this.expressiveSynthesis = expressiveSynthesis;
    }

    @GetMapping("/status")
    public SpeechStatus status() {
        return new SpeechStatus(
                transcription.isReady(),
                synthesis.isReady(VoiceProfileId.ASSISTANT),
                expressiveSynthesis.isExpressiveReady(VoiceProfileId.ASSISTANT));
    }

    @PostMapping(
            value = "/transcriptions",
            consumes = "audio/wav",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TranscriptResponse transcribe(@RequestBody byte[] audio) {
        if (!transcription.isReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "STT가 준비되지 않았습니다.");
        }
        if (audio.length == 0 || audio.length > MAX_AUDIO_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "음성 입력 크기가 올바르지 않습니다.");
        }
        return new TranscriptResponse(transcription.transcribe(new AudioInput(audio, "audio/wav")));
    }

    @PostMapping("/segments")
    public List<SpeechSegment> prepare(@Valid @RequestBody PrepareSpeechRequest request) {
        return synthesis.prepare(request.text());
    }

    @PostMapping(value = "/synthesis", produces = "audio/*")
    public ResponseEntity<byte[]> synthesize(@Valid @RequestBody SynthesizeSpeechRequest request) {
        VoiceProfileId voice = new VoiceProfileId(request.voiceProfile());
        if (request.expression() == null && !synthesis.isReady(voice)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "TTS가 준비되지 않았습니다.");
        }
        SpeechSegment segment = new SpeechSegment(request.index(), request.text());
        AudioOutput output;
        if (request.expression() == null) {
            output = synthesis.synthesize(segment, voice);
        } else {
            if (!expressiveSynthesis.isExpressiveReady(voice)) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "표현형 TTS가 준비되지 않았습니다.");
            }
            output = expressiveSynthesis.synthesizeExpressive(new ExpressiveSpeechRequest(
                    segment,
                    voice,
                    new VoiceExpression(
                            request.expression().style(),
                            request.expression().intensity(),
                            request.expression().communicativeIntent())));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(output.mediaType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"speech-" + request.index() + "." + output.fileExtension() + "\"")
                .header("X-Audio-Extension", output.fileExtension())
                .body(output.data());
    }

    public record SpeechStatus(
            boolean transcriptionReady,
            boolean synthesisReady,
            boolean expressiveSynthesisReady) {}
    public record TranscriptResponse(String transcript) {}
    public record PrepareSpeechRequest(@NotBlank String text) {}
    public record SynthesizeSpeechRequest(
            @PositiveOrZero int index,
            @NotBlank String text,
            @NotBlank String voiceProfile,
            VoiceExpressionRequest expression
    ) {
        public SynthesizeSpeechRequest(int index, String text, String voiceProfile) {
            this(index, text, voiceProfile, null);
        }
    }

    public record VoiceExpressionRequest(
            @NotBlank String style,
            double intensity,
            @NotBlank String communicativeIntent
    ) {}
}
