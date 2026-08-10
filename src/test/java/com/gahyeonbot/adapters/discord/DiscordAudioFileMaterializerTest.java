package com.gahyeonbot.adapters.discord;

import com.gahyeonbot.core.speech.AudioOutput;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordAudioFileMaterializerTest {
    @Test
    void materializesBinaryAudioOnlyAtTheDiscordBoundary() throws Exception {
        var materializer = new DiscordAudioFileMaterializer();
        byte[] source = {7, 8, 9};
        var output = new AudioOutput(source, "audio/wav", "wav");
        source[0] = 0;

        Path file = materializer.materialize(output);
        try {
            assertThat(file.getFileName().toString()).endsWith(".wav");
            assertThat(Files.readAllBytes(file)).containsExactly(7, 8, 9);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
