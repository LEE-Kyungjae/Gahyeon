package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.speech.StreamingExpressiveSpeechSynthesisPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UnrealPcmStreamingConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(UnrealPcmStreamingConfiguration.class)
            .withBean(StreamingExpressiveSpeechSynthesisPort.class,
                    () -> mock(StreamingExpressiveSpeechSynthesisPort.class));

    @Test
    void expressiveVoiceWithoutUnrealRuntimeDoesNotRequireAnUnrealExecutor() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).doesNotHaveBean(UnrealPcmStreamCache.class);
        });
    }

    @Test
    void createsThePcmCacheWhenTheUnrealRuntimeExecutorExists() {
        context.withBean("unrealTtsExecutor", Executor.class, () -> Runnable::run)
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasSingleBean(UnrealPcmStreamCache.class);
                });
    }
}
