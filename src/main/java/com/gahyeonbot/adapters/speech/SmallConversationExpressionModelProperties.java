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
@ConfigurationProperties(prefix = "gahyeon.speech.expression-planner.small-model")
public final class SmallConversationExpressionModelProperties {
    private boolean enabled;

    @Size(max = 2_048)
    private String endpoint;

    @Size(max = 4_096)
    private String apiKey;

    @Size(min = 1, max = 200)
    private String modelId = "Qwen/Qwen3-0.6B";

    @Min(50)
    @Max(1_500)
    private int timeoutMillis = 180;

    @Min(256)
    @Max(16_384)
    private int maxResponseBytes = 4_096;
}
