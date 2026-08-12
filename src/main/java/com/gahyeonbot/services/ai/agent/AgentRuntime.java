package com.gahyeonbot.services.ai.agent;

import com.gahyeonbot.core.identity.ActorId;

public interface AgentRuntime {
    /** Whether this runtime can currently admit new cognition work. */
    default boolean isReady() {
        return true;
    }

    AgentResult execute(AgentRequest request);

    default AgentResult execute(AgentRequest request, AgentExecutionControl control) {
        if (control.isCancelled()) throw new AgentStreamCancelledException();
        AgentResult result = execute(request);
        if (control.isCancelled()) throw new AgentStreamCancelledException();
        return result;
    }

    default AgentResult executeStreaming(AgentRequest request, AgentStreamObserver observer) {
        if (observer.isCancelled()) throw new AgentStreamCancelledException();
        AgentResult result = execute(request);
        if (observer.isCancelled()) throw new AgentStreamCancelledException();
        if (result.content() != null && !result.content().isEmpty()) {
            observer.onTextDelta(result.content());
        }
        return result;
    }

    default AgentResult executeStreaming(
            AgentRequest request,
            AgentStreamObserver observer,
            AgentExecutionControl control) {
        if (control.isCancelled()) throw new AgentStreamCancelledException();
        AgentStreamObserver guarded = new AgentStreamObserver() {
            @Override
            public void onTextDelta(String delta) {
                if (isCancelled()) throw new AgentStreamCancelledException();
                observer.onTextDelta(delta);
            }

            @Override
            public boolean isCancelled() {
                return control.isCancelled() || observer.isCancelled();
            }
        };
        AgentResult result = executeStreaming(request, guarded);
        if (guarded.isCancelled()) throw new AgentStreamCancelledException();
        return result;
    }

    AgentResult resume(String runId, ActorId actorId);

    AgentResult resumeBackground(String runId, String backgroundResult);
}
