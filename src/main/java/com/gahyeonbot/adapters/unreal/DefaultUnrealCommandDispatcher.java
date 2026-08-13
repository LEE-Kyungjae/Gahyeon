package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.identity.IdentityResolutionUseCase;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.application.conversation.ConversationStreamingUseCase;
import com.gahyeonbot.core.identity.IdentityProvider;
import com.gahyeonbot.core.session.ClientSource;
import com.gahyeonbot.core.session.ConversationSession;
import com.gahyeonbot.core.session.ConversationSessionId;

import java.util.Map;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public final class DefaultUnrealCommandDispatcher implements UnrealCommandDispatcher {
    private final ConversationStreamingUseCase conversation;
    private final IdentityResolutionUseCase identities;
    private final Executor cognitionExecutor;
    private final UnrealSpeechPreparationPort speech;
    private final UnrealRuntimeMetrics metrics;
    private final int speechSegmentMaxCharacters;
    private final ConcurrentHashMap<String, SessionAdmission> admissions = new ConcurrentHashMap<>();

    public DefaultUnrealCommandDispatcher(
            ConversationStreamingUseCase conversation,
            IdentityResolutionUseCase identities,
            Executor cognitionExecutor,
            UnrealSpeechPreparationPort speech,
            UnrealRuntimeMetrics metrics,
            int speechSegmentMaxCharacters) {
        if (speechSegmentMaxCharacters < 1) {
            throw new IllegalArgumentException("speech segment maximum must be positive");
        }
        this.conversation = conversation;
        this.identities = identities;
        this.cognitionExecutor = cognitionExecutor;
        this.speech = speech;
        this.metrics = metrics;
        this.speechSegmentMaxCharacters = speechSegmentMaxCharacters;
    }

    public DefaultUnrealCommandDispatcher(
            ConversationStreamingUseCase conversation,
            IdentityResolutionUseCase identities,
            Executor cognitionExecutor,
            UnrealSpeechPreparationPort speech,
            UnrealRuntimeMetrics metrics) {
        this(conversation, identities, cognitionExecutor, speech, metrics, 120);
    }

    public DefaultUnrealCommandDispatcher(
            ConversationStreamingUseCase conversation,
            IdentityResolutionUseCase identities,
            Executor cognitionExecutor,
            UnrealSpeechPreparationPort speech) {
        this(conversation, identities, cognitionExecutor, speech, null);
    }

    @Override
    public DispatchResult dispatch(UnrealConversationCommand command) {
        long admittedAtNanos = System.nanoTime();
        SessionAdmission admission = admissions.computeIfAbsent(
                command.sessionId(), ignored -> new SessionAdmission());
        DispatchResult result = admission.admit(command.generation(), command.requestId());
        if (result != DispatchResult.ACCEPTED) return result;
        speech.advanceGeneration(command.sessionId(), command.generation());
        TrackedTask task = new TrackedTask(admission, command.generation(), () -> {
            if (admission.isCurrent(command.generation())) {
                converse(command, () -> admission.isCurrent(command.generation()), admittedAtNanos);
            }
        });
        admission.attach(command.generation(), task);
        try {
            task.bind(submit(task));
        } catch (RejectedExecutionException rejected) {
            admission.rejected(command.generation(), command.requestId(), task);
            return DispatchResult.BACKPRESSURE;
        }
        return DispatchResult.ACCEPTED;
    }

    @Override
    public void advanceGeneration(String sessionId, long generation) {
        if (sessionId == null || sessionId.isBlank() || generation < 0) return;
        admissions.computeIfAbsent(sessionId, ignored -> new SessionAdmission())
                .advance(generation);
        speech.advanceGeneration(sessionId, generation);
    }

    @Override
    public void releaseSession(String sessionId) {
        if (sessionId == null) return;
        SessionAdmission removed = admissions.remove(sessionId);
        if (removed != null) removed.release();
        speech.releaseSession(sessionId);
    }

    private Future<?> submit(Runnable task) {
        if (cognitionExecutor instanceof AsyncTaskExecutor async) return async.submit(task);
        if (cognitionExecutor instanceof ExecutorService service) return service.submit(task);
        cognitionExecutor.execute(task);
        return null;
    }

    private void converse(
            UnrealConversationCommand command,
            BooleanSupplier currentGeneration,
            long admittedAtNanos) {
        var actorId = identities.resolveExternal(
                IdentityProvider.UNREAL,
                command.installationId(),
                command.displayName(),
                null);
        var session = new ConversationSession(
                ConversationSessionId.fromExternal(ClientSource.UNREAL, command.sessionId()),
                actorId,
                ClientSource.UNREAL,
                command.modality(),
                Map.of(
                        "unreal.installationId", command.installationId(),
                        "unreal.generation", Long.toString(command.generation())));
        conversation.converseStreaming(
                new ConversationRequest(
                        command.requestId(),
                        session,
                        command.displayName(),
                        command.text()),
                new UnrealStreamingSpeechObserver(
                        command.sessionId(),
                        command.requestId(),
                        command.generation(),
                        speech,
                        currentGeneration,
                        speechSegmentMaxCharacters,
                        metrics,
                        admittedAtNanos));
    }

    private static final class SessionAdmission {
        private static final int MAX_REMEMBERED_REQUESTS = 256;
        private long generation = -1;
        private String admittedRequestId;
        private boolean released;
        private final LinkedHashSet<String> requestIds = new LinkedHashSet<>();
        private final Set<TrackedTask> active = new HashSet<>();

        synchronized DispatchResult admit(long candidateGeneration, String requestId) {
            if (requestIds.contains(requestId)) return DispatchResult.DUPLICATE;
            if (candidateGeneration < generation) return DispatchResult.STALE;
            advance(candidateGeneration);
            if (admittedRequestId != null) return DispatchResult.DUPLICATE;
            admittedRequestId = requestId;
            requestIds.add(requestId);
            while (requestIds.size() > MAX_REMEMBERED_REQUESTS) {
                requestIds.remove(requestIds.iterator().next());
            }
            return DispatchResult.ACCEPTED;
        }

        synchronized void advance(long candidateGeneration) {
            if (candidateGeneration <= generation) return;
            generation = candidateGeneration;
            admittedRequestId = null;
            active.forEach(TrackedTask::cancel);
            active.clear();
        }

        synchronized boolean isCurrent(long candidateGeneration) {
            return !released && candidateGeneration == generation;
        }

        synchronized void attach(long candidateGeneration, TrackedTask task) {
            if (candidateGeneration == generation) active.add(task);
            else task.cancel();
        }

        synchronized void completed(long candidateGeneration, TrackedTask task) {
            if (candidateGeneration == generation && task != null) active.remove(task);
        }

        synchronized void rejected(
                long candidateGeneration,
                String requestId,
                TrackedTask task) {
            if (candidateGeneration != generation || !requestId.equals(admittedRequestId)) return;
            active.remove(task);
            admittedRequestId = null;
            requestIds.remove(requestId);
        }

        synchronized void release() {
            released = true;
            active.forEach(TrackedTask::cancel);
            active.clear();
        }
    }

    private final class TrackedTask implements Runnable {
        private final SessionAdmission admission;
        private final long generation;
        private final Runnable delegate;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Future<?> future;
        private volatile Thread runner;

        private TrackedTask(SessionAdmission admission, long generation, Runnable delegate) {
            this.admission = admission;
            this.generation = generation;
            this.delegate = delegate;
        }

        @Override
        public void run() {
            runner = Thread.currentThread();
            try {
                if (!cancelled.get()) delegate.run();
            } finally {
                runner = null;
                admission.completed(generation, this);
            }
        }

        void bind(Future<?> candidate) {
            future = candidate;
            if (candidate != null && cancelled.get()) {
                candidate.cancel(true);
                removeQueued(candidate);
            }
        }

        void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            Future<?> current = future;
            if (current != null) {
                current.cancel(runner != Thread.currentThread());
                removeQueued(current);
            }
            if (metrics != null) metrics.cognitionCancelled();
        }

        private void removeQueued(Future<?> candidate) {
            if (candidate instanceof Runnable queued
                    && cognitionExecutor instanceof ThreadPoolTaskExecutor springExecutor) {
                springExecutor.getThreadPoolExecutor().remove(queued);
            }
        }
    }
}
