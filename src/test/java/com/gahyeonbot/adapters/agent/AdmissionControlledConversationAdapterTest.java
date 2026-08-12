package com.gahyeonbot.adapters.agent;

import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.session.*;
import com.gahyeonbot.services.ai.ConversationAdmissionService;
import com.gahyeonbot.services.ai.agent.AgentResult;
import com.gahyeonbot.services.ai.agent.AgentModality;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdmissionControlledConversationAdapterTest {
    @Test
    void forwardsAnOptionalToolScopeToTheAdmissionService() throws Exception {
        ConversationAdmissionService legacy = mock(ConversationAdmissionService.class);
        when(legacy.chatResult("message:1", "session-1", AgentModality.TEXT,
                new ActorId(20), "tester", 10L, "안녕"))
                .thenReturn(new AgentResult("run-1", "반가워요.", List.of("weather"), Duration.ofMillis(4)));
        var adapter = new AdmissionControlledConversationAdapter(legacy);

        var response = adapter.execute(request(
                ClientSource.DISCORD,
                Map.of("agent.toolScopeId", "10")));

        assertThat(response.runId()).isEqualTo("run-1");
        assertThat(response.tools()).containsExactly("weather");
        verify(legacy).chatResult("message:1", "session-1", AgentModality.TEXT,
                new ActorId(20), "tester", 10L, "안녕");
    }

    @Test
    void supportsAHeadlessClientWithoutDiscordContext() throws Exception {
        ConversationAdmissionService legacy = mock(ConversationAdmissionService.class);
        when(legacy.chatResult("message:1", "session-1", AgentModality.TEXT,
                new ActorId(20), "tester", null, "안녕"))
                .thenReturn(new AgentResult("run-2", "반가워요.", List.of(), Duration.ZERO));
        var adapter = new AdmissionControlledConversationAdapter(legacy);

        var response = adapter.execute(request(ClientSource.HEADLESS, Map.of()));

        assertThat(response.runId()).isEqualTo("run-2");
        verify(legacy).chatResult("message:1", "session-1", AgentModality.TEXT,
                new ActorId(20), "tester", null, "안녕");
    }

    @Test
    void forwardsProviderDeltasThroughThePlatformNeutralStreamingPort() throws Exception {
        ConversationAdmissionService admission = mock(ConversationAdmissionService.class);
        when(admission.chatResultStreaming(
                eq("message:1"), eq("session-1"), eq(AgentModality.TEXT), eq(new ActorId(20)),
                eq("tester"), isNull(), eq("안녕"), any())).thenAnswer(invocation -> {
            com.gahyeonbot.services.ai.agent.AgentStreamObserver observer = invocation.getArgument(7);
            observer.onTextDelta("첫 문장.");
            observer.onTextDelta("둘째 문장.");
            return new AgentResult(
                    "run-stream", "첫 문장.둘째 문장.", List.of(), Duration.ofMillis(7));
        });
        var adapter = new AdmissionControlledConversationAdapter(admission);
        var deltas = new ArrayList<String>();

        var response = adapter.executeStreaming(
                request(ClientSource.UNREAL, Map.of()), deltas::add);

        assertThat(deltas).containsExactly("첫 문장.", "둘째 문장.");
        assertThat(response.content()).isEqualTo("첫 문장.둘째 문장.");
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
