package com.gahyeonbot.adapters.unreal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Hard lifecycle bounds for one client-owned streaming transcription utterance. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "gahyeon.unreal.streaming-stt")
public class UnrealStreamingSttProperties {
    /** Time allowed for a newly established socket to submit its first stream start. */
    @Min(2)
    @Max(60)
    private int initialStartSeconds = 10;

    @Min(5)
    @Max(300)
    private int maximumStreamSeconds = 120;

    /** Total authenticated Streaming STT sockets admitted by one Backend instance. */
    @Min(1)
    @Max(1024)
    private int maximumConnections = 32;

    /** Shared drain workers; each socket retains its own serial order. */
    @Min(1)
    @Max(16)
    private int outboundThreads = 4;

    @Min(0)
    @Max(512)
    private int outboundExecutorQueueCapacity = 32;

    @Min(1)
    @Max(256)
    private int outboundPerConnectionQueueCapacity = 64;
}
