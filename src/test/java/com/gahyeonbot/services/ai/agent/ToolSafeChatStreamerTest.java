package com.gahyeonbot.services.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ToolSafeChatStreamerTest {
    private final Prompt prompt = new Prompt("질문");

    @Test
    void streamsTextChunksAndReturnsTheAggregatedAssistantMessage() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(prompt)).thenReturn(Flux.just(text("첫 문장."), text("둘째 문장.")));
        var deltas = new ArrayList<String>();

        ChatResponse response = new ToolSafeChatStreamer(model, true).call(prompt, deltas::add);

        assertThat(deltas).containsExactly("첫 문장.", "둘째 문장.");
        assertThat(response.getResult().getOutput().getText()).isEqualTo("첫 문장.둘째 문장.");
        verify(model, never()).call(prompt);
    }

    @Test
    void keepsToolCallStepsOutOfTheSpeakableStream() {
        ChatModel model = mock(ChatModel.class);
        var toolCall = new AssistantMessage.ToolCall(
                "call-1", "function", "weather", "{\"city\":\"Seoul\"}");
        when(model.stream(prompt)).thenReturn(Flux.just(new ChatResponse(List.of(
                new Generation(new AssistantMessage("", Map.of(), List.of(toolCall)))))));
        var deltas = new ArrayList<String>();

        ChatResponse response = new ToolSafeChatStreamer(model, true).call(prompt, deltas::add);

        assertThat(deltas).isEmpty();
        assertThat(response.getResult().getOutput().hasToolCalls()).isTrue();
    }

    @Test
    void rejectsAProviderThatViolatesTheDeclaredExclusiveContract() {
        ChatModel model = mock(ChatModel.class);
        var toolCall = new AssistantMessage.ToolCall("call-1", "function", "weather", "{}");
        var tool = new ChatResponse(List.of(new Generation(
                new AssistantMessage("", Map.of(), List.of(toolCall)))));
        when(model.stream(prompt)).thenReturn(Flux.just(text("임시 답변"), tool));

        assertThatThrownBy(() -> new ToolSafeChatStreamer(model, true).call(prompt, delta -> {}))
                .hasMessage("provider mixed tool calls and speakable text in one streamed step");
    }

    @Test
    void usesCommittedSynchronousResponseUntilCapabilityIsExplicitlyEnabled() {
        ChatModel model = mock(ChatModel.class);
        ChatResponse committed = text("최종 답변");
        when(model.call(prompt)).thenReturn(committed);
        var deltas = new ArrayList<String>();

        ChatResponse response = new ToolSafeChatStreamer(model, false).call(prompt, deltas::add);

        assertThat(response).isSameAs(committed);
        assertThat(deltas).isEmpty();
        verify(model, never()).stream(prompt);
    }

    @Test
    void classifiesModelTransportFailureAsProviderFailure() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(prompt)).thenThrow(new IllegalStateException("offline"));

        assertThatThrownBy(() -> new ToolSafeChatStreamer(model, false).call(prompt, delta -> {}))
                .isInstanceOf(ModelProviderException.class)
                .hasRootCauseMessage("offline");
    }

    @Test
    void observerFailureIsNotMisclassifiedAsProviderFailure() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(prompt)).thenReturn(Flux.just(text("delta")));

        assertThatThrownBy(() -> new ToolSafeChatStreamer(model, true).call(
                prompt, delta -> { throw new IllegalStateException("renderer closed"); }))
                .isInstanceOf(AgentStreamObserverDeliveryException.class)
                .hasRootCauseMessage("renderer closed");
    }

    private ChatResponse text(String value) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(value))));
    }
}
