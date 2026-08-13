package com.gahyeonbot.services.ai.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.concurrent.atomic.AtomicReference;

/** Executes one model step and exposes text only under an explicit tool/text exclusivity contract. */
final class ToolSafeChatStreamer {
    private final ChatModel chatModel;
    private final boolean toolTextExclusive;

    ToolSafeChatStreamer(ChatModel chatModel, boolean toolTextExclusive) {
        this.chatModel = chatModel;
        this.toolTextExclusive = toolTextExclusive;
    }

    ChatResponse call(Prompt prompt, AgentStreamObserver observer) {
        try {
            return callProvider(prompt, observer);
        } catch (AgentStreamCancelledException
                 | AgentStreamObserverDeliveryException
                 | ToolStreamingContractViolationException known) {
            throw known;
        } catch (RuntimeException providerFailure) {
            throw new ModelProviderException(providerFailure);
        }
    }

    private ChatResponse callProvider(Prompt prompt, AgentStreamObserver observer) {
        if (!toolTextExclusive || observer == null) return chatModel.call(prompt);
        if (observer.isCancelled()) throw new AgentStreamCancelledException();

        var aggregate = new AtomicReference<ChatResponse>();
        var mode = new AtomicReference<>(StepMode.UNKNOWN);
        var sanitizer = new IncrementalFinalAnswerSanitizer();
        var chunks = chatModel.stream(prompt).doOnNext(response -> {
            if (observer.isCancelled()) throw new AgentStreamCancelledException();
            if (response == null || response.getResult() == null) return;
            AssistantMessage output = response.getResult().getOutput();
            if (output == null) return;
            if (output.hasToolCalls()) {
                if (mode.get() == StepMode.TEXT) contractViolation();
                mode.set(StepMode.TOOL);
            }
            String text = output.getText();
            if (text == null || text.isEmpty()) return;
            if (mode.get() == StepMode.TOOL) contractViolation();
            mode.set(StepMode.TEXT);
            emit(observer, sanitizer.accept(text));
        });
        new MessageAggregator().aggregate(chunks, aggregate::set).blockLast();
        if (observer.isCancelled()) throw new AgentStreamCancelledException();
        emit(observer, sanitizer.finish());
        ChatResponse result = aggregate.get();
        if (result == null || result.getResult() == null) {
            throw new IllegalStateException("streamed model response is empty");
        }
        return result;
    }

    private void emit(AgentStreamObserver observer, String delta) {
        if (delta == null || delta.isEmpty()) return;
        try {
            observer.onTextDelta(delta);
        } catch (RuntimeException deliveryFailure) {
            throw new AgentStreamObserverDeliveryException(deliveryFailure);
        }
    }

    private void contractViolation() {
        throw new ToolStreamingContractViolationException();
    }

    private enum StepMode { UNKNOWN, TEXT, TOOL }
}
