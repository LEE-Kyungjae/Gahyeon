package com.gahyeonbot.services.ai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingModelVerificationTest {
    @Test
    void requiresAnEnabledExactNonBlankProviderAndModelMatch() {
        assertThat(StreamingModelVerification.allows(
                true, "https://provider.example/api/", "provider/model-v1",
                "https://provider.example/api", "provider/model-v1"))
                .isTrue();
        assertThat(StreamingModelVerification.allows(
                false, "https://provider.example/api", "provider/model-v1",
                "https://provider.example/api", "provider/model-v1"))
                .isFalse();
        assertThat(StreamingModelVerification.allows(
                true, "https://other.example/api", "provider/model-v1",
                "https://provider.example/api", "provider/model-v1"))
                .isFalse();
        assertThat(StreamingModelVerification.allows(
                true, "https://provider.example/api", "provider/model-v2",
                "https://provider.example/api", "provider/model-v1"))
                .isFalse();
        assertThat(StreamingModelVerification.allows(
                true, "https://provider.example/api", "provider/model-v1", " ", "provider/model-v1"))
                .isFalse();
        assertThat(StreamingModelVerification.allows(
                true, "https://provider.example/api", "provider/model-v1",
                "https://provider.example/api", " "))
                .isFalse();
    }
}
