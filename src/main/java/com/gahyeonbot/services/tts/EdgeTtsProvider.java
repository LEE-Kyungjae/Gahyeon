package com.gahyeonbot.services.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class EdgeTtsProvider implements TtsProvider {
    private final TtsProperties properties;

    @Override public String name() { return "edge"; }
    @Override public boolean isReady() { return properties.isEnabled(); }

    @Override
    public Path synthesize(String text) throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "gahyeonbot-tts");
        Files.createDirectories(dir);
        Path audio = Files.createTempFile(dir, "tts_edge_", ".mp3");
        Process process = null;
        try {
            var edge = properties.getEdge();
            if (edge.getEndpoint() != null && !edge.getEndpoint().isBlank()) {
                var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));
                factory.setReadTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                byte[] requestBody = new ObjectMapper().writeValueAsBytes(java.util.Map.of(
                        "text", text,
                        "voice", edge.getVoice(),
                        "rate", edge.getRate(),
                        "pitch", edge.getPitch()));
                headers.setContentLength(requestBody.length);
                ResponseEntity<byte[]> response = new RestTemplate(factory).exchange(
                        edge.getEndpoint(), HttpMethod.POST,
                        new HttpEntity<>(requestBody, headers),
                        byte[].class);
                byte[] bytes = response.getBody();
                if (bytes == null || bytes.length < 256) {
                    throw new IllegalStateException("Edge TTS sidecar produced empty audio");
                }
                Files.write(audio, bytes);
                return audio;
            }

            List<String> command = new ArrayList<>();
            command.add(edge.getBin());
            command.add("--voice");
            command.add(edge.getVoice());
            command.add("--rate");
            command.add(edge.getRate());
            command.add("--pitch");
            command.add(edge.getPitch());
            command.add("--text");
            command.add(text);
            command.add("--write-media");
            command.add(audio.toString());

            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final Process runningProcess = process;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try { runningProcess.getInputStream().transferTo(output); } catch (Exception ignored) {}
            });
            boolean complete = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!complete) {
                process.destroyForcibly();
                throw new IllegalStateException("Edge TTS timeout");
            }
            reader.join(200);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Edge TTS failed: " + output.toString(StandardCharsets.UTF_8));
            }
            if (Files.size(audio) < 256) throw new IllegalStateException("Edge TTS produced empty audio");
            return audio;
        } catch (Exception e) {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(audio);
            throw e;
        }
    }
}
