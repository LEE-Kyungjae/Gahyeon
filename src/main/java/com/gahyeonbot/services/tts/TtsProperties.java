package com.gahyeonbot.services.tts;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * TTS 설정.
 * - Edge TTS + (optional) KSS sentence splitting via python script.
 */
@Component
@ConfigurationProperties(prefix = "tts")
@Getter
@Setter
public class TtsProperties {
    private boolean enabled = true;
    /** edge, custom, or voicebox. */
    private String provider = "voicebox";
    private boolean fallbackToEdge = true;

    /** Max input chars for a single /tts request. */
    private int maxChars = 400;

    /** Soft cap per segment before we chunk further. */
    private int segmentMaxChars = 120;

    /** Process timeout for splitter + synthesis. */
    private int timeoutSeconds = 20;

    private final Edge edge = new Edge();
    private final Custom custom = new Custom();
    private final Voicebox voicebox = new Voicebox();
    private final Kss kss = new Kss();

    @Getter
    @Setter
    public static class Edge {
        /** edge-tts executable. */
        private String bin = "edge-tts";
        /** Optional HTTP sidecar endpoint returning MP3 bytes. */
        private String endpoint;

        /** Korean female neural voice. */
        private String voice = "ko-KR-SunHiNeural";

        /** Speech rate, e.g. +0%, -10%. */
        private String rate = "+0%";

        /** Speech pitch, e.g. +0Hz, -20Hz. */
        private String pitch = "+0Hz";
    }

    @Getter
    @Setter
    public static class Custom {
        /** HTTP inference endpoint accepting JSON and returning raw audio bytes. */
        private String endpoint;
        /** Optional bearer token for a private inference server. */
        private String apiKey;
        /** Server-side model name or mounted model path alias. */
        private String model;
        /** Optional immutable runtime model digest. When set, the response must echo it. */
        private String modelSha256;
        /** Optional immutable runtime config digest. When set, the response must echo it. */
        private String configSha256;
        /** Voice/speaker identifier expected by the inference engine. */
        private String speakerId;
        /** wav or mp3. */
        private String format = "wav";
        private int timeoutSeconds = 60;
    }

    @Getter
    @Setter
    public static class Voicebox {
        /** Voicebox backend root URL, without a trailing slash. */
        private String baseUrl = "http://127.0.0.1:17493";
        /** Voicebox cloned profile selected for Discord responses. */
        private String profileId = "1df376d5-c74d-415c-a2f0-fdb1654f7331";
        /** Fallback lookup name used when an imported profile gets a new ID. */
        private String profileName = "내 목소리 (녹음 9)";
        private String language = "ko";
        private String engine = "qwen";
        private String modelSize = "0.6B";
        private boolean normalize = true;
        /** Includes queueing, model loading, and synthesis time. */
        private int timeoutSeconds = 300;
        private int pollMillis = 500;
    }

    @Getter
    @Setter
    public static class Kss {
        /** Python executable. */
        private String python = "python3";

        /** Splitter script path (copied into image as /app/tts_split.py). */
        private String script = "/app/tts_split.py";
    }
}
