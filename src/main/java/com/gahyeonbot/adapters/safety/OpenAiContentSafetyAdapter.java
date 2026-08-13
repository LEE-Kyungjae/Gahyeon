package com.gahyeonbot.adapters.safety;

import com.gahyeonbot.application.conversation.ContentSafetyPort;
import com.gahyeonbot.config.AppCredentialsConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** OpenAI Moderation wire adapter. No provider-specific type escapes this package. */
@Component
@ConditionalOnProperty(
        name = "gahyeon.content-safety.provider",
        havingValue = "openai",
        matchIfMissing = true)
public final class OpenAiContentSafetyAdapter implements ContentSafetyPort {
    private static final String ENDPOINT = "https://api.openai.com/v1/moderations";
    private static final long MINIMUM_TIMEOUT_MILLIS = 100;
    private static final long MAXIMUM_TIMEOUT_MILLIS = 5_000;

    private final String apiKey;
    private final RestTemplate restTemplate;
    private final ContentSafetyProviderCircuit circuit;

    @Autowired
    public OpenAiContentSafetyAdapter(
            AppCredentialsConfig credentials,
            @Value("${gahyeon.content-safety.connect-timeout-millis:300}") long connectTimeoutMillis,
            @Value("${gahyeon.content-safety.read-timeout-millis:700}") long readTimeoutMillis,
            @Value("${gahyeon.content-safety.failure-cooldown-millis:30000}") long cooldownMillis) {
        this(credentials.getOpenaiApiKey(), client(connectTimeoutMillis, readTimeoutMillis),
                new ContentSafetyProviderCircuit(cooldownMillis));
    }

    OpenAiContentSafetyAdapter(String apiKey, RestTemplate restTemplate) {
        this(apiKey, restTemplate, new ContentSafetyProviderCircuit(30_000));
    }

    OpenAiContentSafetyAdapter(
            String apiKey,
            RestTemplate restTemplate,
            ContentSafetyProviderCircuit circuit) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.restTemplate = restTemplate;
        this.circuit = circuit;
    }

    static long boundedTimeoutMillis(long value) {
        return Math.max(MINIMUM_TIMEOUT_MILLIS, Math.min(MAXIMUM_TIMEOUT_MILLIS, value));
    }

    private static RestTemplate client(long connectTimeoutMillis, long readTimeoutMillis) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(boundedTimeoutMillis(connectTimeoutMillis)));
        requestFactory.setReadTimeout(Duration.ofMillis(boundedTimeoutMillis(readTimeoutMillis)));
        return new RestTemplate(requestFactory);
    }

    @Override
    public Decision evaluate(String text) {
        if (apiKey.isBlank() || apiKey.startsWith("your_") || text == null || text.isBlank()) {
            return Decision.UNAVAILABLE;
        }
        if (!circuit.tryAcquire()) return Decision.UNAVAILABLE;
        var response = request(text);
        circuit.success();
        Map<String, Object> body = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful() || body == null) {
            return Decision.UNAVAILABLE;
        }
        Object resultsValue = body.get("results");
        if (!(resultsValue instanceof List<?> results) || results.isEmpty()) {
            return Decision.UNAVAILABLE;
        }
        Object first = results.get(0);
        if (!(first instanceof Map<?, ?> result)
            || !(result.get("flagged") instanceof Boolean flagged)) {
            return Decision.UNAVAILABLE;
        }
        return flagged ? Decision.UNSAFE : Decision.SAFE;
    }

    private org.springframework.http.ResponseEntity<Map<String, Object>> request(String text) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            return restTemplate.exchange(
                    ENDPOINT,
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("input", text), headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RuntimeException failure) {
            circuit.failure();
            throw failure;
        }
    }
}
