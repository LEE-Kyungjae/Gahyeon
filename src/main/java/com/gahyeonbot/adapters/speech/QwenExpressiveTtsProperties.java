package com.gahyeonbot.adapters.speech;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "gahyeon.speech.expressive.qwen")
public class QwenExpressiveTtsProperties {
    private boolean enabled;

    @Size(max = 2_048)
    private String endpoint;

    @Size(max = 4_096)
    private String apiKey;

    @Size(max = 200)
    private String modelId;

    @Size(max = 40)
    private String quantization;

    @Min(500)
    @Max(120_000)
    private int timeoutMillis = 30_000;

    @Min(1_024)
    @Max(33_554_432)
    private int maxAudioBytes = 16 * 1024 * 1024;
}
