package com.gahyeonbot.adapters.safety;

import com.gahyeonbot.application.conversation.ContentSafetyPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class OpenAiContentSafetyAdapterTest {
    @Test
    void networkTimeoutsAreBoundedForTheRealtimeAdmissionPath() {
        assertThat(OpenAiContentSafetyAdapter.boundedTimeoutMillis(-1)).isEqualTo(100);
        assertThat(OpenAiContentSafetyAdapter.boundedTimeoutMillis(700)).isEqualTo(700);
        assertThat(OpenAiContentSafetyAdapter.boundedTimeoutMillis(60_000)).isEqualTo(5_000);
    }

    @Test
    void mapsProviderFlagWithoutLeakingWireTypesIntoThePort() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://api.openai.com/v1/moderations"))
                .andExpect(header("Authorization", "Bearer secret"))
                .andExpect(jsonPath("$.input").value("unsafe input"))
                .andRespond(withSuccess("{\"results\":[{\"flagged\":true}]}", MediaType.APPLICATION_JSON));

        var adapter = new OpenAiContentSafetyAdapter("secret", http);

        assertThat(adapter.evaluate("unsafe input")).isEqualTo(ContentSafetyPort.Decision.UNSAFE);
        server.verify();
    }

    @Test
    void missingCredentialsAndMalformedSuccessfulResponsesAreUnavailable() {
        assertThat(new OpenAiContentSafetyAdapter("", new RestTemplate()).evaluate("hello"))
                .isEqualTo(ContentSafetyPort.Decision.UNAVAILABLE);

        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://api.openai.com/v1/moderations"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThat(new OpenAiContentSafetyAdapter("secret", http).evaluate("hello"))
                .isEqualTo(ContentSafetyPort.Decision.UNAVAILABLE);
        server.verify();
    }

    @Test
    void explicitDisabledAdapterLeavesTheDeterministicPolicyInCharge() {
        assertThat(new DisabledContentSafetyAdapter().evaluate("anything"))
                .isEqualTo(ContentSafetyPort.Decision.UNAVAILABLE);
    }

    @Test
    void providerFailureOpensCircuitAndFollowingRequestSkipsHttp() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("https://api.openai.com/v1/moderations"))
                .andRespond(withServerError());
        var adapter = new OpenAiContentSafetyAdapter(
                "secret", http, new ContentSafetyProviderCircuit(30_000));

        assertThatThrownBy(() -> adapter.evaluate("first"))
                .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
        assertThat(adapter.evaluate("second"))
                .isEqualTo(ContentSafetyPort.Decision.UNAVAILABLE);
        server.verify();
    }
}
