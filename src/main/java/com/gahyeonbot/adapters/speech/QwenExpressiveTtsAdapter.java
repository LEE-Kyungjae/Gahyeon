package com.gahyeonbot.adapters.speech;

import com.gahyeonbot.application.speech.ExpressiveSpeechSynthesisPort;
import com.gahyeonbot.core.speech.AudioOutput;
import com.gahyeonbot.core.speech.ExpressiveSpeechRequest;
import com.gahyeonbot.core.speech.VoiceProfileId;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fail-closed adapter for a separately hosted, quantized Qwen expressive TTS worker. */
public final class QwenExpressiveTtsAdapter implements ExpressiveSpeechSynthesisPort {
    static final String VOICE_PROFILE_HEADER = "X-Gahyeon-Voice-Profile";
    static final String MODEL_HEADER = "X-Gahyeon-Model-Id";
    static final String QUANTIZATION_HEADER = "X-Gahyeon-Quantization";

    private final QwenExpressiveTtsProperties properties;
    private final URI endpoint;
    private final RestTemplate client;

    public QwenExpressiveTtsAdapter(QwenExpressiveTtsProperties properties) {
        this(properties, client(properties));
    }

    QwenExpressiveTtsAdapter(QwenExpressiveTtsProperties properties, RestTemplate client) {
        this.properties = properties;
        this.endpoint = endpoint(properties.getEndpoint());
        this.client = client;
        requireIdentity("modelId", properties.getModelId());
        requireIdentity("quantization", properties.getQuantization());
        if (properties.getMaxAudioBytes() < 1_024
                || properties.getMaxAudioBytes() > 33_554_432) {
            throw new IllegalArgumentException("Qwen audio limit is out of range");
        }
        client.getInterceptors().add((request, body, execution) ->
                bounded(execution.execute(request, body), properties.getMaxAudioBytes()));
    }

    @Override
    public boolean isReady(VoiceProfileId voiceProfile) {
        return properties.isEnabled() && endpoint != null && voiceProfile != null;
    }

    @Override
    public AudioOutput synthesize(ExpressiveSpeechRequest request) {
        if (!isReady(request.voiceProfile())) throw new IllegalStateException("Qwen expressive TTS is not ready");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.parseMediaType("audio/wav")));
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            headers.setBearerAuth(properties.getApiKey().trim());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", request.segment().text());
        payload.put("voiceProfile", request.voiceProfile().value());
        payload.put("style", request.expression().style());
        payload.put("intensity", request.expression().intensity());
        payload.put("communicativeIntent", request.expression().communicativeIntent());
        payload.put("modelId", properties.getModelId().trim());
        payload.put("quantization", properties.getQuantization().trim());
        payload.put("responseFormat", "wav");

        ResponseEntity<byte[]> response = client.exchange(
                endpoint, HttpMethod.POST, new HttpEntity<>(Map.copyOf(payload), headers), byte[].class);
        verifyIdentity(response.getHeaders(), request.voiceProfile());
        byte[] audio = response.getBody();
        if (audio == null || audio.length < 44 || audio.length > properties.getMaxAudioBytes()) {
            throw new IllegalStateException("Qwen expressive TTS returned invalid audio size");
        }
        if (audio[0] != 'R' || audio[1] != 'I' || audio[2] != 'F' || audio[3] != 'F'
                || audio[8] != 'W' || audio[9] != 'A' || audio[10] != 'V' || audio[11] != 'E') {
            throw new IllegalStateException("Qwen expressive TTS did not return PCM WAV");
        }
        return new AudioOutput(audio, "audio/wav", "wav");
    }

    private void verifyIdentity(HttpHeaders headers, VoiceProfileId requestedVoice) {
        requireEqual(VOICE_PROFILE_HEADER, requestedVoice.value(), headers.getFirst(VOICE_PROFILE_HEADER));
        requireEqual(MODEL_HEADER, properties.getModelId().trim(), headers.getFirst(MODEL_HEADER));
        requireEqual(QUANTIZATION_HEADER, properties.getQuantization().trim(),
                headers.getFirst(QUANTIZATION_HEADER));
    }

    private static void requireEqual(String label, String expected, String observed) {
        if (!expected.equals(observed)) {
            throw new IllegalStateException(label + " response identity mismatch");
        }
    }

    private static void requireIdentity(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private static URI endpoint(String value) {
        if (value == null || value.isBlank()) return null;
        URI uri = URI.create(value.trim());
        return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())
                ? uri : null;
    }

    private static RestTemplate client(QwenExpressiveTtsProperties properties) {
        int timeout = properties.getTimeoutMillis();
        if (timeout < 500 || timeout > 120_000) {
            throw new IllegalArgumentException("Qwen timeout is out of range");
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeout));
        factory.setReadTimeout(Duration.ofMillis(timeout));
        return new RestTemplate(factory);
    }

    private static ClientHttpResponse bounded(ClientHttpResponse response, int maximumBytes) {
        long contentLength = response.getHeaders().getContentLength();
        if (contentLength > maximumBytes) {
            response.close();
            throw new IllegalStateException("Qwen expressive TTS response exceeds size limit");
        }
        return new ClientHttpResponse() {
            @Override public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
                return response.getStatusCode();
            }
            @Override public String getStatusText() throws IOException { return response.getStatusText(); }
            @Override public void close() { response.close(); }
            @Override public HttpHeaders getHeaders() { return response.getHeaders(); }
            @Override public InputStream getBody() throws IOException {
                return new BoundedInputStream(response.getBody(), maximumBytes);
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

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) account(1);
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) account(count);
            return count;
        }

        private void account(long count) throws IOException {
            consumed += count;
            if (consumed > maximumBytes) {
                throw new IOException("Qwen expressive TTS response exceeds size limit");
            }
        }
    }
}
