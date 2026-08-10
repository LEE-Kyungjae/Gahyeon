package com.gahyeonbot.adapters.agent;

import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.session.*;
import com.gahyeonbot.services.ai.ConversationAdmissionService;
import com.gahyeonbot.services.ai.agent.AgentResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdmissionControlledConversationAdapterTest {
    @Test
    void forwardsAnOptionalToolScopeToTheAdmissionService() throws Exception {
        ConversationAdmissionService legacy = mock(ConversationAdmissionService.class);
        when(legacy.chatResult("message:1", 20L, "tester", 10L, "안녕"))
                .thenReturn(new AgentResult("run-1", "반가워요.", List.of("weather"), Duration.ofMillis(4)));
        var adapter = new AdmissionControlledConversationAdapter(legacy);

        var response = adapter.execute(request(
                ClientSource.DISCORD,
                Map.of("agent.toolScopeId", "10")));

        assertThat(response.runId()).isEqualTo("run-1");
        assertThat(response.tools()).containsExactly("weather");
        verify(legacy).chatResult("message:1", 20L, "tester", 10L, "안녕");
    }

    @Test
    void supportsAHeadlessClientWithoutDiscordContext() throws Exception {
        ConversationAdmissionService legacy = mock(ConversationAdmissionService.class);
        when(legacy.chatResult("message:1", 20L, "tester", null, "안녕"))
                .thenReturn(new AgentResult("run-2", "반가워요.", List.of(), Duration.ZERO));
        var adapter = new AdmissionControlledConversationAdapter(legacy);

        var response = adapter.execute(request(ClientSource.HEADLESS, Map.of()));

        assertThat(response.runId()).isEqualTo("run-2");
        verify(legacy).chatResult("message:1", 20L, "tester", null, "안녕");
    }

    private static ConversationRequest request(ClientSource source, Map<String, String> context) {
        return new ConversationRequest(
                "message:1",
                new ConversationSession(
                        new ConversationSessionId("session-1"),
                        new ActorId(20),
                        source,
                        ConversationModality.TEXT,
                        context),
                "tester",
                "안녕");
    }
}
