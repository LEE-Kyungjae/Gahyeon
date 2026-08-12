package com.gahyeonbot.services.assistant;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "assistant")
public class AssistantProperties {
    private boolean enabled;
    private int maxUtteranceSeconds = 20;
    private long silenceMillis = 900;
    private boolean speakResponses = true;
    private String ttsProvider = "edge";
    private long responseAcknowledgementMillis = 1_500;
    private String responseAcknowledgementText = "잠시만요, 확인해 볼게요.";
    private int maxAiRequestsPerMinute = 12;
    private long duplicateTranscriptMillis = 10_000;
    private int minTranscriptCharacters = 2;
    private long fragmentMergeMillis = 3_500;
    private final Vad vad = new Vad();
    private final Stt stt = new Stt();
    private final OpenRouter openrouter = new OpenRouter();

    @Getter
    @Setter
    public static class Stt {
        private boolean enabled;
        private String baseUrl = "https://api.openai.com/v1";
        private String endpoint = "/audio/transcriptions";
        private String apiKey;
        private String model = "gpt-4o-mini-transcribe";
        private String language = "ko";
        private String prompt = "";
        private int timeoutSeconds = 8;
        private boolean apiKeyRequired = true;
        private String fallbackBaseUrl = "";
        private String fallbackEndpoint = "/transcribe";
        private String fallbackModel = "sensevoice-small-int8";
        private final Realtime realtime = new Realtime();
    }

    @Getter
    @Setter
    public static class Realtime {
        private boolean enabled;
        private String url = "wss://api.openai.com/v1/realtime?model=gpt-live-transcribe";
        private String model = "gpt-live-transcribe";
        private String delay = "low";
        private int targetSampleRate = 24_000;
        private int maximumPendingSends = 16;
        private int finalTimeoutSeconds = 10;
    }

    @Getter
    @Setter
    public static class Vad {
        private boolean enabled = true;
        private int hopSize = 256;
        private float threshold = 0.5f;
        private long minSpeechMillis = 300;
        private long transcriptionSilenceMillis = 700;
        private long endSilenceMillis = 1_200;
        private long shortSpeechMillis = 1_000;
        private long shortSpeechEndSilenceMillis = 2_000;
        private long preRollMillis = 240;
    }

    @Getter
    @Setter
    public static class OpenRouter {
        private boolean enabled;
        private String apiKey;
        private String baseUrl = "https://openrouter.ai/api/v1";
        private String model;
        private int timeoutSeconds = 60;
    }
}
