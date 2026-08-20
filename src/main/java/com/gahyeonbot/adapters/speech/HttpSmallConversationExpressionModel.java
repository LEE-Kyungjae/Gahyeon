package com.gahyeonbot.adapters.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.speech.ConversationExpressionModel;
import com.gahyeonbot.application.speech.ConversationExpressionModelRequest;
import com.gahyeonbot.core.speech.VoiceExpression;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Strict JSON adapter for a tiny local model worker. Transport failures are handled by the calling policy. */
public final class HttpSmallConversationExpressionModel implements ConversationExpressionModel {
    static final String MODEL_HEADER = "X-Gahyeon-Model-Id";

    private final SmallConversationExpressionModelProperties properties;
    private final URI endpoint;
    private final ObjectMapper objectMapper;
    private final RestTemplate client;

    public HttpSmallConversationExpressionModel(
            SmallConversationExpressionModelProperties properties,
            ObjectMapper objectMapper) {
        this(properties, objectMapper, client(properties));
    }

    HttpSmallConversationExpressionModel(
            SmallConversationExpressionModelProperties properties,
            ObjectMapper objectMapper,
            RestTemplate client) {
        this.properties = properties;
        this.endpoint = endpoint(properties.getEndpoint());
        this.objectMapper = objectMapper;
        this.client = client;
        if (properties.getModelId() == null || properties.getModelId().isBlank()) {
            throw new IllegalArgumentException("small expression model id is required");
        }
        client.getInterceptors().add((request, body, execution) ->
                bounded(execution.execute(request, body), properties.getMaxResponseBytes()));
    }

    @Override
    public Optional<VoiceExpression> plan(ConversationExpressionModelRequest request) {
        if (!properties.isEnabled() || endpoint == null) return Optional.empty();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            headers.setBearerAuth(properties.getApiKey().trim());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getModelId().trim());
        payload.put("characterId", request.characterId());
        payload.put("expressionProfile", request.expressionProfile());
        payload.put("primary", request.primary());
        payload.put("utterance", request.utterance());
        payload.put("activity", request.activity());
        payload.put("valence", request.valence());
        payload.put("arousal", request.arousal());
        payload.put("familiarity", request.familiarity());
        payload.put("trust", request.trust());
        payload.put("affinity", request.affinity());
        payload.put("tension", request.tension());
        payload.put("fallback", Map.of(
                "style", request.fallbackStyle(),
                "intensity", request.fallbackIntensity(),
                "communicativeIntent", request.fallbackIntent()));

        var response = client.exchange(endpoint, HttpMethod.POST,
                new HttpEntity<>(Map.copyOf(payload), headers), byte[].class);
        if (!properties.getModelId().trim().equals(response.getHeaders().getFirst(MODEL_HEADER))) {
            throw new IllegalStateException("small expression model identity mismatch");
        }
        byte[] body = response.getBody();
        if (body == null || body.length == 0 || body.length > properties.getMaxResponseBytes()) {
            throw new IllegalStateException("small expression model returned an invalid response size");
        }
        return Optional.of(parse(body));
    }

    private VoiceExpression parse(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!root.isObject() || root.size() != 3
                    || !root.path("style").isTextual()
                    || !root.path("intensity").isNumber()
                    || !root.path("communicativeIntent").isTextual()) {
                throw new IllegalStateException("small expression model response schema is invalid");
            }
            return new VoiceExpression(root.path("style").textValue(), root.path("intensity").doubleValue(),
                    root.path("communicativeIntent").textValue());
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("small expression model response is not valid JSON", failure);
        }
    }

    private static RestTemplate client(SmallConversationExpressionModelProperties properties) {
        int timeout = properties.getTimeoutMillis();
        if (timeout < 50 || timeout > 1_500) {
            throw new IllegalArgumentException("small expression model timeout is out of range");
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeout));
        factory.setReadTimeout(Duration.ofMillis(timeout));
        return new RestTemplate(factory);
    }

    private static URI endpoint(String value) {
        if (value == null || value.isBlank()) return null;
        URI uri = URI.create(value.trim());
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("small expression model endpoint must use http or https");
        }
        return uri;
    }

    private static ClientHttpResponse bounded(ClientHttpResponse response, int maximumBytes) {
        long contentLength = response.getHeaders().getContentLength();
        if (contentLength > maximumBytes) {
            response.close();
            throw new IllegalStateException("small expression model response exceeds size limit");
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
                throw new IOException("small expression model response exceeds size limit");
            }
        }
    }
}
