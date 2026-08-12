package com.gahyeonbot.services.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantProviderReadinessTest {
    @Test
    void providersStayDisabledWithoutKeys() {
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        properties.getStt().setEnabled(true);
        properties.getOpenrouter().setEnabled(true);

        assertThat(new OpenAiTranscriptionProvider(properties, new ObjectMapper()).isReady()).isFalse();
    }

    @Test
    void providersBecomeReadyOnlyWithExplicitConfiguration() {
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        properties.getStt().setEnabled(true);
        properties.getStt().setApiKey("stt-key");
        properties.getOpenrouter().setEnabled(true);
        properties.getOpenrouter().setApiKey("openrouter-key");
        properties.getOpenrouter().setModel("provider/model");

        assertThat(new OpenAiTranscriptionProvider(properties, new ObjectMapper()).isReady()).isTrue();
    }
}
