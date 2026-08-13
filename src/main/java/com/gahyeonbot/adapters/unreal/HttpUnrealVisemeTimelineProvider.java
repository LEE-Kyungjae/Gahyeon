package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.speech.AudioOutput;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Exact forced-alignment adapter. Audio digest prevents timelines crossing concurrent requests. */
public final class HttpUnrealVisemeTimelineProvider implements UnrealVisemeTimelinePort {
    private final UnrealVisemeAlignmentProperties properties;
    private final RestTemplate client;
    private final URI endpoint;

    public HttpUnrealVisemeTimelineProvider(UnrealVisemeAlignmentProperties properties) {
        this.properties = properties;
        URI endpoint = endpoint(properties.getEndpoint());
        if (!properties.isEnabled() || endpoint == null) {
            throw new IllegalArgumentException("enabled viseme alignment requires an HTTP(S) endpoint");
        }
        requireInRange("viseme aligner timeout", properties.getTimeoutMillis(), 100, 5_000);
        requireInRange("viseme aligner audio limit", properties.getMaxAudioBytes(), 1, 33_554_432);
        requireInRange("viseme aligner response limit", properties.getMaxResponseBytes(), 1_024, 1_048_576);
        this.endpoint = endpoint;
        int timeoutMillis = properties.getTimeoutMillis();
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        this.client = new RestTemplate(requestFactory);
        this.client.getInterceptors().add((request, body, execution) ->
                bounded(execution.execute(request, body), properties.getMaxResponseBytes()));
    }

    @Override
    public List<UnrealVisemeCue> align(String text, AudioOutput audio) {
        byte[] bytes = audio.data();
        if (bytes.length > Math.max(1, properties.getMaxAudioBytes())) {
            throw new IllegalArgumentException("audio exceeds forced-aligner request limit");
        }
        String digest = sha256(bytes);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            headers.setBearerAuth(properties.getApiKey().trim());
        }
        Map<String, Object> request = Map.of(
                "text", text,
                "audioBase64", Base64.getEncoder().encodeToString(bytes),
                "mediaType", audio.mediaType(),
                "audioSha256", digest);
        ResponseEntity<AlignmentResponse> response = client.exchange(
                endpoint, HttpMethod.POST,
                new HttpEntity<>(request, headers), AlignmentResponse.class);
        AlignmentResponse body = response.getBody();
        if (body == null || !digest.equals(body.audioSha256()) || body.cues() == null) {
            throw new IllegalStateException("forced-aligner response does not match requested audio");
        }
        return body.cues().stream()
                .map(cue -> new UnrealVisemeCue(
                        cue.semantic(), cue.atMs(), cue.durationMs(), cue.weight()))
                .toList();
    }

    @Override
    public String source() {
        return "provider";
    }

    private static URI endpoint(String value) {
        if (value == null || value.isBlank()) return null;
        URI uri = URI.create(value.trim());
        return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())
                ? uri : null;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireInRange(String label, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    label + " must be between " + minimum + " and " + maximum);
        }
    }

    private static ClientHttpResponse bounded(ClientHttpResponse response, int maximumBytes) {
        long contentLength = response.getHeaders().getContentLength();
        if (contentLength > maximumBytes) {
            response.close();
            throw new IllegalStateException("forced-aligner response exceeds size limit");
        }
        return new ClientHttpResponse() {
            @Override
            public HttpStatusCode getStatusCode() throws IOException {
                return response.getStatusCode();
            }

            @Override
            public String getStatusText() throws IOException {
                return response.getStatusText();
            }

            @Override
            public void close() {
                response.close();
            }

            @Override
            public InputStream getBody() throws IOException {
                return new BoundedInputStream(response.getBody(), maximumBytes);
            }

            @Override
            public HttpHeaders getHeaders() {
                return response.getHeaders();
            }
        };
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long maximumBytes;
        private long consumed;

        private BoundedInputStream(InputStream input, long maximumBytes) {
            super(input);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) account(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) account(count);
            return count;
        }

        private void account(long count) throws IOException {
            consumed += count;
            if (consumed > maximumBytes) {
                throw new IOException("forced-aligner response exceeds size limit");
            }
        }
    }

    public record AlignmentResponse(String audioSha256, List<AlignmentCue> cues) {}
    public record AlignmentCue(String semantic, long atMs, long durationMs, double weight) {}
}
