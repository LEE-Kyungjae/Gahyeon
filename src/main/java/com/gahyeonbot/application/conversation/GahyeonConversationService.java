package com.gahyeonbot.application.conversation;

import com.gahyeonbot.application.behavior.ConversationPresencePort;
import com.gahyeonbot.application.event.GahyeonEventPublisher;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.core.conversation.ConversationUseCase;
import com.gahyeonbot.core.event.GahyeonEventDraft;
import com.gahyeonbot.core.event.GahyeonEventTypes;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class GahyeonConversationService implements ConversationUseCase, ConversationStreamingUseCase {
    private final ConversationAgentPort agentPort;
    private final GahyeonEventPublisher events;
    private final ConversationPresencePort presence;

    @Autowired
    public GahyeonConversationService(
            ConversationAgentPort agentPort,
            GahyeonEventPublisher events,
            ConversationPresencePort presence) {
        this.agentPort = agentPort;
        this.events = events;
        this.presence = presence;
    }

    GahyeonConversationService(
            ConversationAgentPort agentPort,
            GahyeonEventPublisher events) {
        this(agentPort, events, session -> ConversationPresencePort.PresenceLease.NOOP);
    }

    @Override
    public ConversationResponse converse(ConversationRequest request) {
        return execute(request, null);
    }

    @Override
    public ConversationResponse converseStreaming(
            ConversationRequest request,
            ConversationStreamObserver observer) {
        if (observer == null) throw new IllegalArgumentException("stream observer가 필요합니다.");
        return execute(request, observer);
    }

    private ConversationResponse execute(
            ConversationRequest request,
            ConversationStreamObserver observer) {
        DeltaTrackingObserver tracking = observer == null ? null : new DeltaTrackingObserver(observer);
        try (var ignored = presence.enter(request.session())) {
            events.publish(new GahyeonEventDraft(
                    GahyeonEventTypes.CONVERSATION_STARTED,
                    request.session().id(),
                    request.requestId(),
                    Map.of(
                            "source", request.session().source().name().toLowerCase(),
                            "modality", request.session().modality().name().toLowerCase(),
                            "actorId", request.session().actorId().value())));
            publishCharacterState(request, "thinking");
            ConversationResponse response = agentPort instanceof StreamingConversationAgentPort streaming
                    && tracking != null
                    ? streaming.executeStreaming(request, tracking)
                    : agentPort.execute(request);
            if (tracking != null && !tracking.receivedDelta()) {
                tracking.onTextDelta(response.content());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            if (response.runId() != null) payload.put("runId", response.runId());
            payload.put("content", response.content());
            payload.put("tools", response.tools());
            payload.put("durationMillis", response.duration().toMillis());
            events.publish(new GahyeonEventDraft(
                    GahyeonEventTypes.CONVERSATION_COMPLETED,
                    request.session().id(),
                    request.requestId(),
                    payload));
            // Cognition completion is not Speaking. The local audio-device
            // callback owns that Reflex transition if prepared speech arrives.
            publishCharacterState(request, "idle");
            if (tracking != null) tracking.onCompleted(response);
            return response;
        } catch (RuntimeException failure) {
            boolean cancelled = tracking != null && tracking.isCancelled();
            events.publish(new GahyeonEventDraft(
                    cancelled
                            ? GahyeonEventTypes.CONVERSATION_CANCELLED
                            : GahyeonEventTypes.CONVERSATION_FAILED,
                    request.session().id(),
                    request.requestId(),
                    Map.of(
                            "errorType", failure.getClass().getSimpleName(),
                            "message", failure.getMessage() == null ? "" : failure.getMessage())));
            publishCharacterState(request, "idle");
            if (tracking != null) {
                if (cancelled) tracking.onCancelled();
                else tracking.onFailed(failure);
            }
            throw failure;
        }
    }

    private void publishCharacterState(ConversationRequest request, String state) {
        events.publish(new GahyeonEventDraft(
                GahyeonEventTypes.CHARACTER_STATE_TARGET,
                request.session().id(),
                request.requestId(),
                Map.of("state", state, "priority", 50)));
    }

    private static final class DeltaTrackingObserver implements ConversationStreamObserver {
        private final ConversationStreamObserver delegate;
        private boolean receivedDelta;
        private boolean available = true;

        private DeltaTrackingObserver(ConversationStreamObserver delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onTextDelta(String delta) {
            if (!available || delta == null || delta.isEmpty()) return;
            receivedDelta = true;
            invoke(() -> delegate.onTextDelta(delta));
        }

        @Override
        public void onCompleted(ConversationResponse response) {
            invoke(() -> delegate.onCompleted(response));
        }

        @Override
        public void onFailed(RuntimeException failure) {
            invoke(() -> delegate.onFailed(failure));
        }

        @Override
        public void onCancelled() {
            invoke(delegate::onCancelled);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }

        boolean receivedDelta() {
            return receivedDelta;
        }

        private void invoke(Runnable callback) {
            if (!available) return;
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // Presentation observers must never change the cognition result.
                available = false;
            }
        }
    }
}
