package com.gahyeonbot.adapters.speech;

import com.gahyeonbot.application.speech.SpeechSynthesisPort;
import com.gahyeonbot.core.speech.*;
import com.gahyeonbot.services.assistant.AssistantProperties;
import com.gahyeonbot.services.tts.TtsService;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class TtsServiceSynthesisAdapter implements SpeechSynthesisPort {
    static final long MAX_SYNTHESIZED_AUDIO_BYTES = 16L * 1024 * 1024;

    private final TtsService ttsService;
    private final AssistantProperties assistantProperties;

    public TtsServiceSynthesisAdapter(TtsService ttsService, AssistantProperties assistantProperties) {
        this.ttsService = ttsService;
        this.assistantProperties = assistantProperties;
    }

    @Override
    public boolean isReady(VoiceProfileId voiceProfile) {
        return ttsService.isEnabled() && supported(voiceProfile);
    }

    @Override
    public List<SpeechSegment> prepare(String text) {
        try {
            List<String> parts = ttsService.prepareSegments(text);
            List<SpeechSegment> segments = new ArrayList<>(parts.size());
            for (int index = 0; index < parts.size(); index++) {
                segments.add(new SpeechSegment(index, parts.get(index)));
            }
            return List.copyOf(segments);
        } catch (Exception exception) {
            throw new SpeechSynthesisException("음성 문장 분리에 실패했습니다.", exception);
        }
    }

    @Override
    public AudioOutput synthesize(SpeechSegment segment, VoiceProfileId voiceProfile) {
        if (!supported(voiceProfile)) {
            throw new IllegalArgumentException("지원하지 않는 voice profile: " + voiceProfile.value());
        }
        Path audio = null;
        try {
            audio = VoiceProfileId.ASSISTANT.equals(voiceProfile)
                    ? ttsService.synthesizeSegmentToAudio(
                            segment.text(), assistantProperties.getTtsProvider())
                    : ttsService.synthesizeSegmentToAudio(segment.text());
            long audioBytes = Files.size(audio);
            if (audioBytes == 0 || audioBytes > MAX_SYNTHESIZED_AUDIO_BYTES) {
                throw new IllegalStateException(
                        "합성 음성 크기가 허용 범위를 벗어났습니다: " + audioBytes);
            }
            String extension = extension(audio);
            return new AudioOutput(
                    Files.readAllBytes(audio), mediaType(extension), extension);
        } catch (Exception exception) {
            throw new SpeechSynthesisException("음성 합성에 실패했습니다.", exception);
        } finally {
            if (audio != null) {
                try {
                    Files.deleteIfExists(audio);
                } catch (Exception ignored) {
                    // The provider artifact is best-effort cleanup after bytes are captured.
                }
            }
        }
    }

    private static boolean supported(VoiceProfileId profile) {
        return VoiceProfileId.DEFAULT.equals(profile) || VoiceProfileId.ASSISTANT.equals(profile);
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length()
                ? name.substring(dot + 1).toLowerCase()
                : "wav";
    }

    private static String mediaType(String extension) {
        return switch (extension) {
            case "mp3" -> "audio/mpeg";
            case "ogg", "opus" -> "audio/ogg";
            default -> "audio/wav";
        };
    }

    public static class SpeechSynthesisException extends RuntimeException {
        public SpeechSynthesisException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
