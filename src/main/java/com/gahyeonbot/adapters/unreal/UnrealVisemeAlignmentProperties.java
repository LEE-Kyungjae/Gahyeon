package com.gahyeonbot.adapters.unreal;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "gahyeon.unreal.viseme")
public class UnrealVisemeAlignmentProperties {
    private boolean enabled;

    @Size(max = 2_048)
    private String endpoint;

    @Size(max = 4_096)
    private String apiKey;

    @Min(100)
    @Max(5_000)
    private int timeoutMillis = 1_500;

    /** Maximum time speech publication may wait for exact alignment. */
    @Min(25)
    @Max(1_000)
    private int playbackDeadlineMillis = 250;

    @Min(1)
    @Max(8)
    private int threads = 2;

    @Min(0)
    @Max(64)
    private int queueCapacity = 8;

    @Min(1)
    @Max(33_554_432)
    private int maxAudioBytes = 2_000_000;

    @Min(1_024)
    @Max(1_048_576)
    private int maxResponseBytes = 131_072;
}
