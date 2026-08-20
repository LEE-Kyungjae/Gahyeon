package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.speech.StreamingExpressiveSpeechSynthesisPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;

@Configuration
@ConditionalOnBean(StreamingExpressiveSpeechSynthesisPort.class)
public class UnrealPcmStreamingConfiguration {
    @Bean
    @ConditionalOnBean(name = "unrealTtsExecutor")
    UnrealPcmStreamCache unrealPcmStreamCache(
            StreamingExpressiveSpeechSynthesisPort synthesis,
            @Qualifier("unrealTtsExecutor") Executor executor) {
        return new UnrealPcmStreamCache(synthesis, executor, Clock.systemUTC(), Duration.ofMinutes(5));
    }
}
