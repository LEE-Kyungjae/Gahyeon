package com.gahyeonbot.adapters.unreal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Tunable bounds for the Backend side of the real-time Unreal bridge. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "gahyeon.unreal.runtime")
public class UnrealRuntimeProperties {
    @Min(1)
    @Max(16)
    private int cognitionCoreThreads = 1;

    @Min(1)
    @Max(32)
    private int cognitionMaxThreads = 2;

    @Min(0)
    @Max(1024)
    // Zero by default: ThreadPoolExecutor otherwise queues behind an interrupt-ignoring stale
    // provider before it is allowed to grow from coreThreads to maxThreads.
    private int cognitionQueueCapacity = 0;

    @Min(1)
    @Max(16)
    private int ttsThreads = 1;

    @Min(0)
    @Max(256)
    private int ttsQueueCapacity = 8;

    /** Concurrent renderer drains; each renderer remains internally ordered. */
    @Min(1)
    @Max(16)
    private int outboundThreads = 4;

    @Min(0)
    @Max(512)
    private int outboundExecutorQueueCapacity = 32;

    @Min(1)
    @Max(512)
    private int outboundPerRendererQueueCapacity = 64;

    /** Authenticated renderer event sockets admitted by this Backend instance. */
    @Min(1)
    @Max(512)
    private int maximumRendererConnections = 64;

    /** Desktop/Looking Glass and bounded reconnect overlap for one logical session. */
    @Min(1)
    @Max(32)
    private int maximumRendererConnectionsPerSession = 4;

    @Min(1)
    @Max(60)
    private int rendererHelloTimeoutSeconds = 10;

    @Min(15)
    @Max(120)
    private int rendererHeartbeatTimeoutSeconds = 30;

    /** Upper bound before a streamed response fragment is handed to TTS. */
    @Min(24)
    @Max(500)
    private int speechSegmentMaxCharacters = 120;

    @AssertTrue(message = "cognition max threads must be greater than or equal to core threads")
    public boolean isCognitionPoolRangeValid() {
        return cognitionMaxThreads >= cognitionCoreThreads;
    }

    @AssertTrue(message = "renderer per-session connections must not exceed the global limit")
    public boolean isRendererConnectionRangeValid() {
        return maximumRendererConnectionsPerSession <= maximumRendererConnections;
    }
}
