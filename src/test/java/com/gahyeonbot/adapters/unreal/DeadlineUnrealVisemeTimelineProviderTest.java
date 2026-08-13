package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.speech.AudioOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadlineUnrealVisemeTimelineProviderTest {
    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void stop() {
        if (executor != null) executor.shutdown();
    }

    @Test
    void returnsExactTimelineWithinPlaybackDeadline() {
        var expected = List.of(new UnrealVisemeCue("aa", 0, 80, 1.0));
        var provider = new DeadlineUnrealVisemeTimelineProvider(
                (text, audio) -> expected, executor(1, 1), Duration.ofMillis(250));

        assertThat(provider.align("가현", audio())).isEqualTo(expected);
    }

    @Test
    void abandonsSlowAlignmentWithoutWaitingForHttpTimeout() throws Exception {
        var interrupted = new CountDownLatch(1);
        UnrealVisemeTimelinePort slow = (text, audio) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return List.of();
        };
        var provider = new DeadlineUnrealVisemeTimelineProvider(
                slow, executor(1, 0), Duration.ofMillis(25));
        long started = System.nanoTime();

        assertThatThrownBy(() -> provider.align("가현", audio()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deadline");
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(500);
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void preservesProviderSourceForQualityMetrics() {
        UnrealVisemeTimelinePort exact = new UnrealVisemeTimelinePort() {
            @Override
            public List<UnrealVisemeCue> align(String text, AudioOutput audio) {
                return List.of();
            }

            @Override
            public String source() {
                return "provider";
            }
        };
        var provider = new DeadlineUnrealVisemeTimelineProvider(
                exact, executor(1, 1), Duration.ofMillis(250));

        assertThat(provider.source()).isEqualTo("provider");
    }

    private ThreadPoolTaskExecutor executor(int threads, int queueCapacity) {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.initialize();
        return executor;
    }

    private static AudioOutput audio() {
        return new AudioOutput(new byte[]{1, 2, 3}, "audio/wav", "wav");
    }
}
