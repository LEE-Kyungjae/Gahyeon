package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.speech.AudioInput;
import com.gahyeonbot.core.speech.TranscriptionUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/gahyeon/unreal/speech")
@ConditionalOnProperty(
        name = {"gahyeon.headless.enabled", "gahyeon.unreal.websocket.enabled"},
        havingValue = "true")
public class UnrealSpeechController {
    private static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024;

    private final UnrealAudioCache audio;
    private final TranscriptionUseCase transcription;
    private final UnrealRuntimeMetrics metrics;

    public UnrealSpeechController(
            UnrealAudioCache audio,
            TranscriptionUseCase transcription,
            UnrealRuntimeMetrics metrics) {
        this.audio = audio;
        this.transcription = transcription;
        this.metrics = metrics;
    }

    @GetMapping("/status")
    public SpeechStatus status() {
        return new SpeechStatus(transcription.isReady());
    }

    @PostMapping(
            value = "/transcriptions",
            consumes = "audio/wav",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public TranscriptResponse transcribe(@RequestBody byte[] wavAudio) {
        long startedAt = System.nanoTime();
        String result = "provider_failure";
        try {
            if (!transcription.isReady()) {
                result = "unavailable";
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "STT가 준비되지 않았습니다.");
            }
            if (wavAudio.length == 0 || wavAudio.length > MAX_AUDIO_BYTES) {
                result = "invalid_size";
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "음성 입력 크기가 올바르지 않습니다.");
            }
            TranscriptResponse response = new TranscriptResponse(
                    transcription.transcribe(new AudioInput(wavAudio, "audio/wav")));
            result = "success";
            return response;
        } finally {
            metrics.sttRequest(result, System.nanoTime() - startedAt);
        }
    }

    @GetMapping("/audio/{audioId}")
    public ResponseEntity<byte[]> audio(@PathVariable String audioId) {
        return audio.get(audioId)
                .map(output -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .contentType(MediaType.parseMediaType(output.mediaType()))
                        .body(output.data()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record SpeechStatus(boolean transcriptionReady) {}
    public record TranscriptResponse(String transcript) {}
}
