package com.gahyeonbot.services.tts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeTtsProviderTest {
    @TempDir Path tempDir;

    @Test
    void interruptionTerminatesTheRunningEdgeProcess() throws Exception {
        Path pidFile = tempDir.resolve("edge.pid");
        Path fakeEdge = tempDir.resolve("fake-edge-tts");
        Files.writeString(fakeEdge, """
                #!/bin/sh
                printf '%%s' \"$$\" > '%s'
                exec sleep 60
                """.formatted(pidFile));
        assertThat(fakeEdge.toFile().setExecutable(true)).isTrue();

        TtsProperties properties = new TtsProperties();
        properties.setTimeoutSeconds(30);
        properties.getEdge().setBin(fakeEdge.toString());
        EdgeTtsProvider provider = new EdgeTtsProvider(properties);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread synthesis = Thread.ofPlatform().start(() -> {
            try {
                provider.synthesize("취소할 음성");
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });

        await(() -> Files.exists(pidFile), Duration.ofSeconds(3));
        long pid = Long.parseLong(Files.readString(pidFile));
        assertThat(ProcessHandle.of(pid)).isPresent();
        synthesis.interrupt();
        synthesis.join(3_000);
        await(() -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) == false,
                Duration.ofSeconds(3));

        assertThat(synthesis.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOf(InterruptedException.class);
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    private static void await(CheckedCondition condition, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.satisfied() && Instant.now().isBefore(deadline)) {
            Thread.sleep(10);
        }
        assertThat(condition.satisfied()).isTrue();
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean satisfied() throws Exception;
    }
}
