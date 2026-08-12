package com.gahyeonbot.services.ai.agent;

/**
 * Cooperative cancellation boundary for one cognition execution.
 *
 * <p>Cancellation is a correctness signal, not a promise that an underlying model provider has
 * stopped consuming work. Implementations may interrupt the caller as a best effort, while the
 * runtime must still check this signal before publishing or committing results.</p>
 */
public interface AgentExecutionControl {
    AgentExecutionControl NONE = () -> false;

    boolean isCancelled();

    /**
     * Registers the ledger cancellation that becomes available after a run has started.
     * Implementations must invoke it immediately when cancellation was already requested.
     */
    default void onRunStarted(String runId, Runnable cancelRun) {
        // Uncancellable/default executions do not retain callbacks.
    }

    /**
     * Runs a terminal commit only while this execution is still current.
     * Implementations used for client generations should make this check atomic with supersession.
     */
    default boolean commitIfActive(Runnable commit) {
        if (isCancelled()) return false;
        commit.run();
        return true;
    }

    /**
     * Claims permission to start one externally visible side effect.
     *
     * <p>A client-generation control must linearize this claim with cancellation. When
     * cancellation wins first this method returns {@code false} and the side effect must not be
     * started. A side effect whose claim won may finish cooperatively, but callers must still
     * suppress all post-cancellation events and terminal commits.</p>
     */
    default boolean tryStartSideEffect() {
        return !isCancelled();
    }
}
