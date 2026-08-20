package com.gahyeonbot.adapters.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.speech.ConversationExpressionModelRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpSmallConversationExpressionModelTest {
    @Test
    void sendsCharacterProfileAndAcceptsOnlyStrictAttestedOutput() {
        RestTemplate client = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
        server.expect(requestTo("http://land:18772/v1/expression-plan"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"model":"Qwen/Qwen3-0.6B","characterId":"gahyeon",
                         "expressionProfile":"gahyeon.metahuman","primary":true,
                         "utterance":"싫어어~","activity":"conversation","valence":0.2,"arousal":0.6,
                         "familiarity":0.7,"trust":0.8,"affinity":0.6,"tension":0.1,
                         "fallback":{"style":"natural","intensity":0.3,
                                     "communicativeIntent":"conversation"}}
                        """))
                .andRespond(withSuccess("""
                        {"style":"fake_cute","intensity":0.72,
                         "communicativeIntent":"playfully_exaggerate_cuteness"}
                        """, MediaType.APPLICATION_JSON)
                        .header(HttpSmallConversationExpressionModel.MODEL_HEADER, "Qwen/Qwen3-0.6B"));
        var model = new HttpSmallConversationExpressionModel(properties(), new ObjectMapper(), client);

        var result = model.plan(request()).orElseThrow();

        assertThat(result.style()).isEqualTo("fake_cute");
        assertThat(result.intensity()).isEqualTo(0.72);
        server.verify();
    }

    @Test
    void rejectsWrongModelAndUnknownResponseFields() {
        RestTemplate wrongModelClient = new RestTemplate();
        MockRestServiceServer.bindTo(wrongModelClient).build()
                .expect(requestTo("http://land:18772/v1/expression-plan"))
                .andRespond(withSuccess("""
                        {"style":"natural","intensity":0.3,"communicativeIntent":"conversation"}
                        """, MediaType.APPLICATION_JSON)
                        .header(HttpSmallConversationExpressionModel.MODEL_HEADER, "other-model"));
        assertThatThrownBy(() -> new HttpSmallConversationExpressionModel(
                properties(), new ObjectMapper(), wrongModelClient).plan(request()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("identity mismatch");

        RestTemplate extraFieldClient = new RestTemplate();
        MockRestServiceServer.bindTo(extraFieldClient).build()
                .expect(requestTo("http://land:18772/v1/expression-plan"))
                .andRespond(withSuccess("""
                        {"style":"natural","intensity":0.3,"communicativeIntent":"conversation","speech":"no"}
                        """, MediaType.APPLICATION_JSON)
                        .header(HttpSmallConversationExpressionModel.MODEL_HEADER, "Qwen/Qwen3-0.6B"));
        assertThatThrownBy(() -> new HttpSmallConversationExpressionModel(
                properties(), new ObjectMapper(), extraFieldClient).plan(request()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("schema");
    }

    private static SmallConversationExpressionModelProperties properties() {
        var properties = new SmallConversationExpressionModelProperties();
        properties.setEnabled(true);
        properties.setEndpoint("http://land:18772/v1/expression-plan");
        return properties;
    }

    private static ConversationExpressionModelRequest request() {
        return new ConversationExpressionModelRequest(
                "gahyeon", "gahyeon.metahuman", true, "싫어어~", "conversation",
                0.2, 0.6, 0.7, 0.8, 0.6, 0.1,
                "natural", 0.3, "conversation");
    }
}
