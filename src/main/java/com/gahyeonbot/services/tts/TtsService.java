package com.gahyeonbot.services.tts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TtsService {
    private final TtsProperties props;
    private final ObjectMapper objectMapper;
    private final List<TtsProvider> providers;

    public boolean isEnabled() {
        return props.isEnabled();
    }

    public int getMaxChars() {
        return props.getMaxChars();
    }

    public List<String> prepareSegments(String text) throws Exception {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        // Fast path: short text skips splitter process entirely.
        if (trimmed.length() <= props.getSegmentMaxChars()) {
            return List.of(trimmed);
        }
        // Starting a Python/KSS process on the response path can add its full
        // timeout before the first audio. Punctuation-aware local splitting is
        // deterministic and keeps voice replies responsive.
        return chunkSentences(splitSentencesLocally(trimmed));
    }

    public Path synthesizeSegmentToAudio(String text) throws Exception {
        return synthesizeSegmentToAudio(text, props.getProvider());
    }

    public Path synthesizeSegmentToAudio(String text, String provider) throws Exception {
        TtsProvider selected = findProvider(provider);
        if (selected.isReady()) {
            try {
                return selected.synthesize(text);
            } catch (Exception e) {
                if (interrupted(e) || identityMismatch(e)) {
                    throw e;
                }
                if (!"edge".equals(selected.name()) && props.isFallbackToEdge()) {
                    log.warn("커스텀 TTS 실패, Edge TTS로 폴백합니다: {}", e.getMessage());
                    return findProvider("edge").synthesize(text);
                }
                throw e;
            }
        }
        if (!"edge".equals(selected.name()) && props.isFallbackToEdge()) {
            log.warn("선택한 TTS 제공자 '{}'가 준비되지 않아 Edge TTS를 사용합니다.", selected.name());
            return findProvider("edge").synthesize(text);
        }
        throw new IllegalStateException("TTS 제공자 '" + selected.name() + "' 설정이 준비되지 않았습니다.");
    }

    private static boolean interrupted(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) return true;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException
                    || current instanceof java.util.concurrent.CancellationException) {
                return true;
            }
        }
        return false;
    }

    private static boolean identityMismatch(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TtsIdentityMismatchException) return true;
        }
        return false;
    }

    private TtsProvider findProvider(String name) {
        String requested = name == null ? "edge" : name.trim().toLowerCase();
        return providers.stream()
                .filter(provider -> provider.name().equals(requested))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 TTS 제공자: " + requested));
    }

    private List<String> splitAndChunk(String text) throws Exception {
        List<String> sentences;
        try {
            sentences = splitSentences(text);
        } catch (Exception e) {
            log.warn("KSS 문장 분리기를 사용할 수 없어 Java 분리기로 대체합니다: {}", e.getMessage());
            sentences = splitSentencesLocally(text);
        }
        return chunkSentences(sentences);
    }

    private List<String> chunkSentences(List<String> sentences) {
        List<String> out = new ArrayList<>();
        int max = Math.max(20, props.getSegmentMaxChars());
        for (String s : sentences) {
            String trimmed = (s == null) ? "" : s.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() <= max) {
                out.add(trimmed);
                continue;
            }
            // Chunk long sentence into fixed windows to avoid synthesis failures.
            for (int i = 0; i < trimmed.length(); i += max) {
                int end = Math.min(trimmed.length(), i + max);
                String part = trimmed.substring(i, end).trim();
                if (!part.isEmpty()) out.add(part);
            }
        }
        return out;
    }

    static List<String> splitSentencesLocally(String text) {
        if (text == null || text.isBlank()) return List.of();
        String[] parts = text.trim().split("(?<=[.!?。！？])\\s+|\\R+");
        List<String> sentences = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) sentences.add(trimmed);
        }
        return sentences.isEmpty() ? List.of(text.trim()) : sentences;
    }

    private List<String> splitSentences(String text) throws Exception {
        String script = props.getKss().getScript();
        // If running outside Docker (dev), allow local path.
        if (!Files.exists(Path.of(script))) {
            script = "scripts/tts_split.py";
        }

        ProcessBuilder pb = new ProcessBuilder(
                props.getKss().getPython(),
                script
        );
        pb.redirectErrorStream(false);
        Process p = pb.start();
        StreamGobbler outGobbler = StreamGobbler.start(p.getInputStream());
        StreamGobbler errGobbler = StreamGobbler.start(p.getErrorStream());
        try (var os = p.getOutputStream()) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }

        boolean ok = p.waitFor(props.getTimeoutSeconds(), TimeUnit.SECONDS);
        if (!ok) {
            p.destroyForcibly();
            throw new IllegalStateException("KSS splitter timeout");
        }
        String stdout = outGobbler.getOutput();
        String stderr = errGobbler.getOutput();
        if (p.exitValue() != 0) {
            throw new IllegalStateException("KSS splitter failed: " + stderr);
        }

        String json = extractJsonPayload(stdout).orElse(stdout);
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            // Include both streams for diagnosis but keep it compact.
            String msg = "KSS splitter returned non-JSON output. stdout=" + abbreviate(stdout) + " stderr=" + abbreviate(stderr);
            throw new IllegalStateException(msg, e);
        }
    }

    private static Optional<String> extractJsonPayload(String s) {
        if (s == null) return Optional.empty();
        int startArr = s.indexOf('[');
        int endArr = s.lastIndexOf(']');
        if (startArr >= 0 && endArr > startArr) {
            return Optional.of(s.substring(startArr, endArr + 1).trim());
        }
        int startObj = s.indexOf('{');
        int endObj = s.lastIndexOf('}');
        if (startObj >= 0 && endObj > startObj) {
            return Optional.of(s.substring(startObj, endObj + 1).trim());
        }
        return Optional.empty();
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        String t = s.replace("\n", "\\n").replace("\r", "\\r");
        int max = 400;
        if (t.length() <= max) return t;
        return t.substring(0, max) + "...";
    }

    private static final class StreamGobbler implements Runnable {
        private final InputStream is;
        private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        private final Thread thread;

        private StreamGobbler(InputStream is) {
            this.is = is;
            this.thread = new Thread(this, "tts-proc-gobbler");
            this.thread.setDaemon(true);
        }

        static StreamGobbler start(InputStream is) {
            StreamGobbler g = new StreamGobbler(is);
            g.thread.start();
            return g;
        }

        @Override
        public void run() {
            try {
                is.transferTo(baos);
            } catch (Exception ignored) {
            }
        }

        String getOutput() {
            try {
                thread.join(200); // best-effort; stream should be closed after process exit
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

}
