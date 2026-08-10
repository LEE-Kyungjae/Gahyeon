package com.gahyeonbot.services.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.core.conversation.ConversationUseCase;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.session.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantProviderReadinessTest {
    @Test
    void providersStayDisabledWithoutKeys() {
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        properties.getStt().setEnabled(true);
        properties.getOpenrouter().setEnabled(true);

        assertThat(new OpenAiTranscriptionProvider(properties, new ObjectMapper()).isReady()).isFalse();
        assertThat(new OpenRouterAssistantProvider(properties, runtimeStub()).isReady()).isFalse();
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
        assertThat(new OpenRouterAssistantProvider(properties, runtimeStub()).isReady()).isTrue();
    }

    @Test
    void voiceGatewayUsesSharedPlatformNeutralConversationCore() {
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        properties.getOpenrouter().setEnabled(true);
        properties.getOpenrouter().setApiKey("openrouter-key");
        properties.getOpenrouter().setModel("provider/model");
        CapturingConversation conversation = new CapturingConversation();

        var request = new ConversationRequest(
                "voice:request-1",
                new ConversationSession(
                        new ConversationSessionId("discord:voice:10"),
                        new ActorId(20),
                        ClientSource.DISCORD,
                        ConversationModality.VOICE,
                        Map.of("agent.toolScopeId", "10")),
                "tester",
                "날씨 알려줘");
        String answer = new OpenRouterAssistantProvider(properties, conversation).chat(request);

        assertThat(answer).isEqualTo("응답");
        assertThat(conversation.request.session().modality()).isEqualTo(ConversationModality.VOICE);
        assertThat(conversation.request.session().id().value()).isEqualTo("discord:voice:10");
        assertThat(conversation.request.session().actorId()).isEqualTo(new ActorId(20));
    }

    private static ConversationUseCase runtimeStub() {
        return request -> null;
    }

    private static final class CapturingConversation implements ConversationUseCase {
        private ConversationRequest request;

        @Override
        public ConversationResponse converse(ConversationRequest request) {
            this.request = request;
            return new ConversationResponse("run", "응답", List.of(), Duration.ofMillis(1));
        }
    }
}
