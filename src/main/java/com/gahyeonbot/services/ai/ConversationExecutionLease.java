package com.gahyeonbot.services.ai;

import com.gahyeonbot.services.ai.agent.AgentExecutionControl;
import com.gahyeonbot.services.ai.agent.AgentStreamCancelledException;
import com.gahyeonbot.services.ai.agent.AgentStreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One in-process actor generation. Provider cancellation remains cooperative/best effort. */
@Slf4j
final class ConversationExecutionLease implements AgentExecutionControl {
    private enum State { ACTIVE, COMMITTED, CANCELLED, COMPLETED }

    private static final class RunnerBinding {
        private final Thread thread;
        private boolean interruptedByLease;

        private RunnerBinding(Thread thread) {
            this.thread = thread;
        }
    }

    static final class Cancellation {
        static final Cancellation NONE = new Cancellation(null, null, null, null);
        private final ConversationExecutionLease owner;
        private final RunnerBinding runnerBinding;
        private final Runnable cancellationAccounting;
        private final Runnable runCancellation;
        private final AtomicBoolean notified = new AtomicBoolean();

        private Cancellation(
                ConversationExecutionLease owner,
                RunnerBinding runnerBinding,
                Runnable cancellationAccounting,
                Runnable runCancellation) {
            this.owner = owner;
            this.runnerBinding = runnerBinding;
            this.cancellationAccounting = cancellationAccounting;
            this.runCancellation = runCancellation;
        }

        void notifyCancellation() {
            if (!notified.compareAndSet(false, true)) return;
            if (owner != null) owner.interruptIfStillBound(runnerBinding);
            invoke(cancellationAccounting, "usage cancellation", null);
            invoke(runCancellation, "agent run cancellation", null);
        }
    }

    private final AgentStreamObserver delegate;
    private final Runnable cancellationAccounting;
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
    private RunnerBinding runnerBinding;
    private Runnable runCancellation;

    ConversationExecutionLease(
            AgentStreamObserver delegate,
            Runnable cancellationAccounting) {
        this.delegate = delegate;
        this.cancellationAccounting = cancellationAccounting;
    }

    void bindRunner(Thread candidate) {
        RunnerBinding binding = candidate == null ? null : new RunnerBinding(candidate);
        boolean interrupt;
        synchronized (this) {
            runnerBinding = binding;
            interrupt = state.get() == State.CANCELLED;
        }
        if (interrupt) interruptIfStillBound(binding);
    }

    void unbindRunner(Thread candidate) {
        boolean clearLeaseInterrupt = false;
        synchronized (this) {
            if (runnerBinding != null && runnerBinding.thread == candidate) {
                clearLeaseInterrupt = runnerBinding.interruptedByLease
                        && candidate == Thread.currentThread();
                runnerBinding = null;
            }
        }
        if (clearLeaseInterrupt) Thread.interrupted();
    }

    AgentStreamObserver streamingObserver() {
        if (delegate == null) throw new IllegalStateException("stream observer가 없습니다.");
        return new AgentStreamObserver() {
            @Override
            public void onTextDelta(String delta) {
                deliver(delta);
            }

            @Override
            public boolean isCancelled() {
                return ConversationExecutionLease.this.isCancelled();
            }
        };
    }

    @Override
    public boolean isCancelled() {
        State current = state.get();
        return current == State.CANCELLED
                || current == State.ACTIVE && delegate != null && delegate.isCancelled();
    }

    @Override
    public void onRunStarted(String runId, Runnable cancelRun) {
        boolean invokeNow;
        synchronized (this) {
            invokeNow = state.get() == State.CANCELLED;
            if (state.get() == State.ACTIVE) runCancellation = cancelRun;
        }
        if (invokeNow) invoke(cancelRun, "agent run cancellation", runId);
    }

    @Override
    public boolean commitIfActive(Runnable commit) {
        if (delegate != null && delegate.isCancelled()) {
            markCancelled().notifyCancellation();
            return false;
        }
        if (!state.compareAndSet(State.ACTIVE, State.COMMITTED)) return false;
        commit.run();
        return true;
    }

    @Override
    public boolean tryStartSideEffect() {
        if (delegate != null && delegate.isCancelled()) {
            markCancelled().notifyCancellation();
            return false;
        }
        return state.compareAndSet(State.ACTIVE, State.ACTIVE);
    }

    boolean complete(Runnable terminalAccounting) {
        State current = state.get();
        if (current == State.ACTIVE) {
            if (delegate != null && delegate.isCancelled()) {
                markCancelled().notifyCancellation();
                return false;
            }
            if (!state.compareAndSet(State.ACTIVE, State.COMMITTED)) {
                current = state.get();
            } else {
                current = State.COMMITTED;
            }
        }
        if (current != State.COMMITTED) return false;
        terminalAccounting.run();
        state.compareAndSet(State.COMMITTED, State.COMPLETED);
        return true;
    }

    void cancel() {
        markCancelled().notifyCancellation();
    }

    Cancellation markCancelled() {
        if (!state.compareAndSet(State.ACTIVE, State.CANCELLED)) return Cancellation.NONE;
        synchronized (this) {
            Cancellation cancellation = new Cancellation(
                    this, runnerBinding, cancellationAccounting, runCancellation);
            runCancellation = null;
            return cancellation;
        }
    }

    private void deliver(String delta) {
        if (state.get() != State.ACTIVE) throw new AgentStreamCancelledException();
        if (delegate.isCancelled()) {
            markCancelled().notifyCancellation();
            throw new AgentStreamCancelledException();
        }
        if (state.get() != State.ACTIVE) throw new AgentStreamCancelledException();
        delegate.onTextDelta(delta);
    }

    private synchronized void interruptIfStillBound(RunnerBinding expected) {
        if (expected == null || runnerBinding != expected) return;
        Thread thread = expected.thread;
        if (thread != Thread.currentThread()) {
            expected.interruptedByLease = true;
            thread.interrupt();
        }
    }

    private static void invoke(Runnable callback, String operation, String runId) {
        if (callback == null) return;
        try {
            callback.run();
        } catch (RuntimeException failure) {
            log.error("Conversation {} failed{}", operation,
                    runId == null ? "" : " run=" + runId, failure);
        }
    }
}
