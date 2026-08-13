package com.gahyeonbot.services.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CustomVoiceTtsProvider implements TtsProvider {
    private static final int MAX_AUDIO_BYTES = 32 * 1024 * 1024;
    private final TtsProperties properties;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "custom"; }

    @Override
    public boolean isReady() {
        var custom = properties.getCustom();
        return properties.isEnabled() && hasText(custom.getEndpoint())
                && hasText(custom.getModel()) && hasText(custom.getSpeakerId());
    }

    @Override
    public Path synthesize(String text) throws Exception {
        if (!isReady()) throw new IllegalStateException("커스텀 음성 서버 설정이 필요합니다.");
        var custom = properties.getCustom();
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(custom.getTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(custom.getTimeoutSeconds()));
        RestTemplate client = new RestTemplate(factory);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.valueOf(mediaType(custom.getFormat()))));
        if (hasText(custom.getApiKey())) headers.setBearerAuth(custom.getApiKey());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("model", custom.getModel());
        body.put("speakerId", custom.getSpeakerId());
        body.put("format", normalizedFormat(custom.getFormat()));

        ResponseEntity<byte[]> response = client.execute(
                custom.getEndpoint(), HttpMethod.POST,
                request -> {
                    request.getHeaders().putAll(headers);
                    objectMapper.writeValue(request.getBody(), body);
                },
                serverResponse -> {
                    byte[] bounded = serverResponse.getBody().readNBytes(MAX_AUDIO_BYTES + 1);
                    if (bounded.length > MAX_AUDIO_BYTES) {
                        throw new IllegalStateException("커스텀 음성 응답이 32 MiB를 초과했습니다.");
                    }
                    return new ResponseEntity<>(
                            bounded, serverResponse.getHeaders(), serverResponse.getStatusCode());
                });
        if (response == null) throw new IllegalStateException("커스텀 음성 서버 응답이 없습니다.");
        verifyResponseIdentity(response.getHeaders(), custom);
        byte[] audio = response.getBody();
        if (audio == null || audio.length < 256) {
            throw new IllegalStateException("커스텀 음성 서버가 빈 오디오를 반환했습니다.");
        }
        verifyAudio(response.getHeaders(), normalizedFormat(custom.getFormat()), audio);

        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "gahyeonbot-tts");
        Files.createDirectories(dir);
        Path path = Files.createTempFile(dir, "tts_custom_", "." + normalizedFormat(custom.getFormat()));
        try {
            Files.write(path, audio);
            return path;
        } catch (Exception e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    private static String normalizedFormat(String format) {
        return "mp3".equalsIgnoreCase(format) ? "mp3" : "wav";
    }

    static void verifyAudio(HttpHeaders headers, String format, byte[] audio) {
        MediaType contentType = headers.getContentType();
        MediaType expected = MediaType.valueOf(mediaType(format));
        if (contentType != null && !expected.isCompatibleWith(contentType)) {
            throw new IllegalStateException(
                    "커스텀 음성 서버 Content-Type이 요청 형식과 다릅니다: " + contentType);
        }
        if (!"wav".equals(format)) return;
        if (audio.length < 44
                || !Arrays.equals(Arrays.copyOfRange(audio, 0, 4),
                        new byte[]{(byte) 'R', (byte) 'I', (byte) 'F', (byte) 'F'})
                || !Arrays.equals(Arrays.copyOfRange(audio, 8, 12),
                        new byte[]{(byte) 'W', (byte) 'A', (byte) 'V', (byte) 'E'})
                || !containsNonEmptyWavDataChunk(audio)) {
            throw new IllegalStateException("커스텀 음성 서버가 유효한 WAV를 반환하지 않았습니다.");
        }
    }

    private static boolean containsNonEmptyWavDataChunk(byte[] audio) {
        int offset = 12;
        while (offset + 8 <= audio.length) {
            long size = Integer.toUnsignedLong(
                    (audio[offset + 4] & 0xff)
                            | ((audio[offset + 5] & 0xff) << 8)
                            | ((audio[offset + 6] & 0xff) << 16)
                            | ((audio[offset + 7] & 0xff) << 24));
            long payloadStart = offset + 8L;
            long payloadEnd = payloadStart + size;
            if (payloadEnd > audio.length) return false;
            if (audio[offset] == 'd' && audio[offset + 1] == 'a'
                    && audio[offset + 2] == 't' && audio[offset + 3] == 'a') {
                return size > 0;
            }
            long next = payloadEnd + (size & 1L);
            if (next > Integer.MAX_VALUE) return false;
            offset = (int) next;
        }
        return false;
    }

    static void verifyResponseIdentity(HttpHeaders headers, TtsProperties.Custom expected) {
        verifyIdentityHeader(headers, "X-Piper-Model", expected.getModel(), true);
        verifyIdentityHeader(
                headers, "X-Piper-Model-SHA256", expected.getModelSha256(), false);
        verifyIdentityHeader(
                headers, "X-Piper-Config-SHA256", expected.getConfigSha256(), false);
    }

    private static void verifyIdentityHeader(
            HttpHeaders headers, String name, String expected, boolean optionalWhenAbsent) {
        String actual = headers.getFirst(name);
        if (!hasText(expected)) return;
        if (!hasText(actual)) {
            if (optionalWhenAbsent) return;
            throw new TtsIdentityMismatchException(
                    "커스텀 음성 서버가 " + name + "을 반환하지 않았습니다.");
        }
        if (!expected.trim().equals(actual.trim())) {
            throw new TtsIdentityMismatchException(
                    "커스텀 음성 서버의 " + name + "이 요청한 release와 다릅니다.");
        }
    }

    private static String mediaType(String format) {
        return "mp3".equalsIgnoreCase(format) ? "audio/mpeg" : "audio/wav";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
