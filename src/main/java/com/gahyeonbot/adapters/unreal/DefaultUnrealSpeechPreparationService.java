package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.speech.SpeechSynthesisUseCase;
import com.gahyeonbot.core.speech.VoiceProfileId;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public final class DefaultUnrealSpeechPreparationService implements UnrealSpeechPreparationPort {
    private static final int MAXIMUM_PENDING_PREPARATIONS_PER_SESSION = 64;
    private final SpeechSynthesisUseCase synthesis;
    private final UnrealAudioCache audio;
    private final UnrealEphemeralBroker outbound;
    private final Executor ttsExecutor;
    private final UnrealRuntimeMetrics metrics;
    private final UnrealVisemeTimelinePort visemes;
    private final ConcurrentHashMap<String, SessionTasks> sessions = new ConcurrentHashMap<>();

    public DefaultUnrealSpeechPreparationService(
            SpeechSynthesisUseCase synthesis,
            UnrealAudioCache audio,
            UnrealEphemeralBroker outbound,
            Executor ttsExecutor,
            UnrealRuntimeMetrics metrics) {
        this(synthesis, audio, outbound, ttsExecutor, metrics,
                UnrealVisemeTimelinePort.unavailable());
    }

    public DefaultUnrealSpeechPreparationService(
            SpeechSynthesisUseCase synthesis,
            UnrealAudioCache audio,
            UnrealEphemeralBroker outbound,
            Executor ttsExecutor,
            UnrealRuntimeMetrics metrics,
            UnrealVisemeTimelinePort visemes) {
        this.synthesis = synthesis;
        this.audio = audio;
        this.outbound = outbound;
        this.ttsExecutor = ttsExecutor;
        this.metrics = metrics;
        this.visemes = visemes;
    }

    @Override
    public void prepare(
            UnrealSpeechPreparationRequest request,
            BooleanSupplier currentGeneration) {
        long requestedAt = System.nanoTime();
        submit(request.sessionId(), request.generation(), currentGeneration,
                () -> synthesize(request, currentGeneration, requestedAt),
                () -> failed(request, "tts_queue_full"), false);
    }

    @Override
    public void advanceGeneration(String sessionId, long generation) {
        if (sessionId == null || sessionId.isBlank() || generation < 0) return;
        sessions.computeIfAbsent(sessionId, ignored -> new SessionTasks()).advance(generation);
    }

    @Override
    public void releaseSession(String sessionId) {
        if (sessionId == null) return;
        SessionTasks removed = sessions.remove(sessionId);
        if (removed != null) removed.release();
    }

    private void submit(
            String sessionId,
            long generation,
            BooleanSupplier externalCurrent,
            Runnable operation,
            Runnable rejectedCallback,
            boolean terminal) {
        SessionTasks owner = sessions.computeIfAbsent(sessionId, ignored -> new SessionTasks());
        TtsTask task = new TtsTask(
                owner, generation, externalCurrent, operation, rejectedCallback, terminal);
        AttachResult result = owner.attach(generation, task);
        if (result == AttachResult.REJECTED) {
            if (task.isCurrent()) rejectedCallback.run();
        } else if (result == AttachResult.SCHEDULE) {
            schedule(task);
        }
    }

    private void schedule(TtsTask task) {
        try {
            task.bind(submitToExecutor(task));
        } catch (RuntimeException rejected) {
            boolean current = task.isCurrent();
            TtsTask next = task.owner.completed(task.generation, task);
            if (current) task.rejectedCallback.run();
            if (next != null) schedule(next);
        }
    }

    private Future<?> submitToExecutor(Runnable task) {
        if (ttsExecutor instanceof AsyncTaskExecutor async) return async.submit(task);
        if (ttsExecutor instanceof ExecutorService service) return service.submit(task);
        ttsExecutor.execute(task);
        return null;
    }

    @Override
    public void finishSequence(
            UnrealSpeechSequenceEndRequest request,
            BooleanSupplier currentGeneration) {
        submit(request.sessionId(), request.generation(), currentGeneration, () -> {
                if (!currentGeneration.getAsBoolean()) return;
                SessionTasks state = sessions.get(request.sessionId());
                String outcome = state != null && state.failed(request.generation())
                        ? "failed" : request.outcome();
                int utteranceCount = state != null
                        ? state.successfulUtteranceCount(request.generation())
                        : request.utteranceCount();
                outbound.publish(
                        request.sessionId(),
                        "speech.sequence.ended",
                        request.correlationId(),
                        Map.of(
                                "generation", request.generation(),
                                "utteranceCount", utteranceCount,
                                "outcome", outcome));
            }, () -> metrics.ttsFailure("tts_queue_full"), true);
    }

    private void synthesize(
            UnrealSpeechPreparationRequest request,
            BooleanSupplier currentGeneration,
            long requestedAt) {
        try {
            if (!healthy(request, currentGeneration)) return;
            VoiceProfileId voice = VoiceProfileId.ASSISTANT;
            if (!synthesis.isReady(voice)) {
                failed(request, "tts_not_ready");
                return;
            }
            var segments = synthesis.prepare(request.text());
            if (segments.isEmpty()) {
                failed(request, "tts_empty");
                return;
            }
            for (int position = 0; position < segments.size(); position++) {
                if (!healthy(request, currentGeneration)) return;
                var segment = segments.get(position);
                long segmentStartedAt = System.nanoTime();
                var output = synthesis.synthesize(segment, voice);
                metrics.ttsSegment(System.nanoTime() - segmentStartedAt);
                if (!healthy(request, currentGeneration)) return;
                if (!("audio/wav".equals(output.mediaType())
                        || "audio/x-wav".equals(output.mediaType()))) {
                    failed(request, "tts_unsupported_media");
                    return;
                }
                if (PcmWavKoreanVisemeTimeline.pcmWavDurationMs(output) <= 0) {
                    failed(request, "tts_invalid_wav");
                    return;
                }
                String audioId = audio.put(output);
                List<Map<String, Object>> timeline = align(segment.text(), output).stream()
                        .map(cue -> Map.<String, Object>of(
                                "semantic", cue.semantic(),
                                "atMs", cue.atMs(),
                                "durationMs", cue.durationMs(),
                                "weight", cue.weight()))
                        .toList();
                metrics.visemeTimeline(timelineSource(timeline));
                SessionTasks state = sessions.get(request.sessionId());
                if (!currentGeneration.getAsBoolean() || state == null) {
                    audio.discard(audioId);
                    return;
                }
                boolean finalSegment = position == segments.size() - 1;
                int admittedRenderers = outbound.publishIf(
                        request.sessionId(),
                        "speech.prepared",
                        request.correlationId(),
                        Map.of(
                                "generation", request.generation(),
                                "utteranceId", audioId,
                                "utteranceIndex", request.utteranceIndex(),
                                "segmentIndex", segment.index(),
                                "segmentCount", segments.size(),
                                "finalSegment", finalSegment,
                                "audio", Map.of(
                                        "url", "/api/gahyeon/unreal/speech/audio/" + audioId,
                                        "mimeType", output.mediaType()),
                                "visemes", timeline),
                        boundedAdmission -> state.tryAdmitPublication(
                                request.generation(), currentGeneration, boundedAdmission));
                if (admittedRenderers < 0) {
                    audio.discard(audioId);
                    return;
                }
                if (admittedRenderers == 0) {
                    audio.discard(audioId);
                    if (!currentGeneration.getAsBoolean()
                            || !state.healthy(request.generation())) return;
                    failed(request, "tts_no_renderer");
                    return;
                }
                if (position == 0) metrics.ttsFirstSegment(System.nanoTime() - requestedAt);
            }
            SessionTasks state = sessions.get(request.sessionId());
            if (state != null) state.markSuccessful(
                    request.generation(), request.utteranceIndex());
        } catch (RuntimeException failure) {
            if (currentGeneration.getAsBoolean()) failed(request, "tts_failed");
        }
    }

    private List<UnrealVisemeCue> align(
            String text,
            com.gahyeonbot.core.speech.AudioOutput output) {
        long startedAt = System.nanoTime();
        String source = visemeSource();
        try {
            long audioDurationMs = PcmWavKoreanVisemeTimeline.pcmWavDurationMs(output);
            if (audioDurationMs <= 0) {
                metrics.visemeAlignment(source, "audio_invalid", System.nanoTime() - startedAt);
                return List.of();
            }
            List<UnrealVisemeCue> timeline = visemes.align(text, output);
            if (timeline == null || timeline.size() > 256) {
                metrics.visemeAlignment(source, "contract_invalid", System.nanoTime() - startedAt);
                return List.of();
            }
            long previousAt = -1;
            for (UnrealVisemeCue cue : timeline) {
                if (cue == null || cue.atMs() < previousAt
                        || cue.atMs() > audioDurationMs
                        || cue.durationMs() > audioDurationMs - cue.atMs()) {
                    metrics.visemeAlignment(source, "contract_invalid", System.nanoTime() - startedAt);
                    return List.of();
                }
                previousAt = cue.atMs();
            }
            metrics.visemeAlignment(
                    source, timeline.isEmpty() ? "empty" : "success", System.nanoTime() - startedAt);
            return List.copyOf(timeline);
        } catch (RuntimeException unavailable) {
            metrics.visemeAlignment(source, "failure", System.nanoTime() - startedAt);
            return List.of();
        }
    }

    private String visemeSource() {
        try {
            String source = visemes.source();
            return source == null ? "other" : source;
        } catch (RuntimeException ignored) {
            return "other";
        }
    }

    private String timelineSource(List<Map<String, Object>> timeline) {
        if (timeline.isEmpty()) return "amplitude";
        return visemeSource();
    }

    private void failed(UnrealSpeechPreparationRequest request, String code) {
        metrics.ttsFailure(code);
        SessionTasks state = sessions.get(request.sessionId());
        if (state != null) state.markFailed(request.generation());
    }

    private boolean healthy(
            UnrealSpeechPreparationRequest request,
            BooleanSupplier externalCurrent) {
        SessionTasks state = sessions.get(request.sessionId());
        return externalCurrent.getAsBoolean() && state != null
                && state.healthy(request.generation());
    }

    private final class TtsTask implements Runnable {
        private final SessionTasks owner;
        private final long generation;
        private final BooleanSupplier externalCurrent;
        private final Runnable operation;
        private final Runnable rejectedCallback;
        private final boolean terminal;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Future<?> future;
        private volatile Thread runner;

        private TtsTask(
                SessionTasks owner,
                long generation,
                BooleanSupplier externalCurrent,
                Runnable operation,
                Runnable rejectedCallback,
                boolean terminal) {
            this.owner = owner;
            this.generation = generation;
            this.externalCurrent = externalCurrent;
            this.operation = operation;
            this.rejectedCallback = rejectedCallback;
            this.terminal = terminal;
        }

        @Override
        public void run() {
            runner = Thread.currentThread();
            try {
                if (isCurrent()) operation.run();
            } finally {
                runner = null;
                TtsTask next = owner.completed(generation, this);
                if (next != null) schedule(next);
            }
        }

        boolean isCurrent() {
            return !cancelled.get() && owner.isCurrent(generation)
                    && externalCurrent.getAsBoolean();
        }

        void bind(Future<?> candidate) {
            future = candidate;
            if (candidate != null && cancelled.get()) candidate.cancel(true);
        }

        void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            Future<?> current = future;
            if (current != null) {
                // Session release can be triggered synchronously by a failed
                // broker publish running inside this exact task. Mark it
                // cancelled without interrupting its own cleanup path.
                current.cancel(runner != Thread.currentThread());
                if (current instanceof Runnable queued
                        && ttsExecutor instanceof ThreadPoolTaskExecutor springExecutor) {
                    // Cancellation makes FutureTask inert, but explicit removal keeps a
                    // new-generation utterance from waiting behind stale queue entries.
                    springExecutor.getThreadPoolExecutor().remove(queued);
                }
            }
            metrics.ttsCancelled();
        }
    }

    private final class SessionTasks {
        private long generation = -1;
        private final java.util.ArrayDeque<TtsTask> ordered = new java.util.ArrayDeque<>();
        private TtsTask head;
        private boolean released;
        private boolean sequenceFailed;
        private final Set<Integer> successfulUtterances = new HashSet<>();

        synchronized void advance(long candidate) {
            if (candidate <= generation) return;
            generation = candidate;
            sequenceFailed = false;
            successfulUtterances.clear();
            ordered.forEach(TtsTask::cancel);
            ordered.clear();
            head = null;
        }

        synchronized AttachResult attach(long candidate, TtsTask task) {
            if (released || candidate < generation) return AttachResult.REJECTED;
            advance(candidate);
            long preparations = ordered.stream().filter(candidateTask -> !candidateTask.terminal).count();
            boolean terminalQueued = ordered.stream().anyMatch(candidateTask -> candidateTask.terminal);
            if ((!task.terminal && preparations >= MAXIMUM_PENDING_PREPARATIONS_PER_SESSION)
                    || (task.terminal && terminalQueued)) return AttachResult.REJECTED;
            ordered.addLast(task);
            if (head != null) return AttachResult.QUEUED;
            head = task;
            return AttachResult.SCHEDULE;
        }

        synchronized boolean isCurrent(long candidate) {
            return !released && candidate == generation;
        }

        synchronized boolean healthy(long candidate) {
            return !released && candidate == generation && !sequenceFailed;
        }

        synchronized boolean tryAdmitPublication(
                long candidate,
                BooleanSupplier externalCurrent,
                Runnable boundedQueueAdmission) {
            if (released || candidate != generation || sequenceFailed
                    || !externalCurrent.getAsBoolean()) return false;
            boundedQueueAdmission.run();
            return true;
        }

        synchronized void markFailed(long candidate) {
            if (!released && candidate == generation) sequenceFailed = true;
        }

        synchronized boolean failed(long candidate) {
            return candidate == generation && sequenceFailed;
        }

        synchronized void markSuccessful(long candidate, int utteranceIndex) {
            if (!released && candidate == generation && !sequenceFailed) {
                successfulUtterances.add(utteranceIndex);
            }
        }

        synchronized int successfulUtteranceCount(long candidate) {
            return candidate == generation ? successfulUtterances.size() : 0;
        }

        synchronized TtsTask completed(long candidate, TtsTask task) {
            ordered.remove(task);
            if (head == task) head = null;
            if (released || candidate != generation || head != null || ordered.isEmpty()) {
                return null;
            }
            head = ordered.peekFirst();
            return head;
        }

        synchronized void release() {
            released = true;
            ordered.forEach(TtsTask::cancel);
            ordered.clear();
            head = null;
        }
    }

    private enum AttachResult { REJECTED, QUEUED, SCHEDULE }
}
