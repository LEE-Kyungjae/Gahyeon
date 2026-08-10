package com.gahyeonbot.adapters.discord;

import com.gahyeonbot.core.speech.AudioOutput;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class DiscordAudioFileMaterializer {
    public Path materialize(AudioOutput audio) throws IOException {
        Path file = Files.createTempFile("gahyeon-discord-audio-", "." + audio.fileExtension());
        boolean written = false;
        try {
            Files.write(file, audio.data());
            written = true;
            return file;
        } finally {
            if (!written) Files.deleteIfExists(file);
        }
    }
}
