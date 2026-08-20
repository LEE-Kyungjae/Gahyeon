package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.speech.SpeechSynthesisUseCase;
import com.gahyeonbot.core.speech.VoiceProfileId;
import com.gahyeonbot.core.speech.ExpressiveSpeechRequest;
import com.gahyeonbot.core.speech.ExpressiveSpeechSynthesisUseCase;

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
    private final ExpressiveSpeechSynthesisUseCase expressiveSynthesis;
    private final UnrealAudioCache audio;
    private final UnrealEphemeralBroker outbound;
    private final Executor ttsExecutor;
    private final UnrealRuntimeMetrics metrics;
    private final UnrealVisemeTimelinePort visemes;
    private final UnrealPcmStreamCache pcmStreams;
    private final ConcurrentHashMap<String, SessionTasks> sessions = new ConcurrentHashMap<>();

    public DefaultUnrealSpeechPreparationService(
            SpeechSynthesisUseCase synthesis,
            UnrealAudioCache audio,
            UnrealEphemeralBroker outbound,
            Executor ttsExecutor,
            UnrealRuntimeMetrics metrics) {
        this(synthesis, null, audio, outbound, ttsExecutor, metrics,
                UnrealVisemeTimelinePort.unavailable(), null);
    }

    public DefaultUnrealSpeechPreparationService(
            SpeechSynthesisUseCase synthesis,
            UnrealAudioCache audio,
            UnrealEphemeralBroker outbound,
            Executor ttsExecutor,
            UnrealRuntimeMetrics metrics,
            UnrealVisemeTimelinePort visemes) {
        this(synthesis, null, audio, outbound, ttsExecutor, metrics, visemes, null);
    }

    public DefaultUnrealSpeechPreparationService(
            SpeechSynthesisUseCase synthesis,
            ExpressiveSpeechSynthesisUseCase expressiveSynthesis,
            UnrealAudioCache audio,
            UnrealEphemeralBroker outbound,
            Executor ttsExecutor,
            UnrealRuntimeMetrics metrics,
            UnrealVisemeTimelinePort visemes) {
        this(synthesis, expressiveSynthesis, audio, outbound, ttsExecutor, metrics, visemes, null);
    }

    public DefaultUnrealSpeechPreparationService(
            SpeechSynthesisUseCase synthesis,
            ExpressiveSpeechSynthesisUseCase expressiveSynthesis,
            UnrealAudioCache audio,
            UnrealEphemeralBroker outbound,
            Executor ttsExecutor,
            UnrealRuntimeMetrics metrics,
            UnrealVisemeTimelinePort visemes,
            UnrealPcmStreamCache pcmStreams) {
        this.synthesis = synthesis;
        this.expressiveSynthesis = expressiveSynthesis;
        this.audio = audio;
        this.outbound = outbound;
        this.ttsExecutor = ttsExecutor;
        this.metrics = metrics;
        this.visemes = visemes;
        this.pcmStreams = pcmStreams;
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
            VoiceProfileId voice = request.voiceProfile();
            boolean expressive = request.expression() != null;
            boolean streaming = expressive && pcmStreams != null && pcmStreams.isReady(voice);
            boolean ready = streaming || (expressive
                    ? expressiveSynthesis != null && expressiveSynthesis.isExpressiveReady(voice)
                    : synthesis.isReady(voice));
            if (!ready) {
                failed(request, "tts_not_ready");
                return;
            }
            var segments = synthesis.prepare(request.text());
            if (segments.isEmpty()) {
                failed(request, "tts_empty");
                return;
            }
            if (streaming) {
                synthesizeStreaming(request, currentGeneration, requestedAt, voice, segments);
                return;
            }
            for (int position = 0; position < segments.size(); position++) {
                if (!healthy(request, currentGeneration)) return;
                var segment = segments.get(position);
                long segmentStartedAt = System.nanoTime();
                var output = expressive
                        ? expressiveSynthesis.synthesizeExpressive(
                                new ExpressiveSpeechRequest(segment, voice, request.expression()))
                        : synthesis.synthesize(segment, voice);
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
                var preparedPayload = new java.util.LinkedHashMap<String, Object>();
                preparedPayload.put("generation", request.generation());
                preparedPayload.put("utteranceId", audioId);
                preparedPayload.put("utteranceIndex", request.utteranceIndex());
                preparedPayload.put("segmentIndex", segment.index());
                preparedPayload.put("segmentCount", segments.size());
                preparedPayload.put("finalSegment", finalSegment);
                preparedPayload.put("voiceProfile", voice.value());
                if (request.expression() != null) {
                    preparedPayload.put("voiceExpression", Map.of(
                            "style", request.expression().style(),
                            "intensity", request.expression().intensity(),
                            "communicativeIntent", request.expression().communicativeIntent()));
                }
                preparedPayload.put("audio", Map.of(
                        "url", "/api/gahyeon/unreal/speech/audio/" + audioId,
                        "mimeType", output.mediaType()));
                preparedPayload.put("visemes", timeline);
                int admittedRenderers = outbound.publishIf(
                        request.sessionId(),
                        "speech.prepared",
                        request.correlationId(),
                        Map.copyOf(preparedPayload),
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

    private void synthesizeStreaming(
            UnrealSpeechPreparationRequest request,
            BooleanSupplier currentGeneration,
            long requestedAt,
            VoiceProfileId voice,
            List<com.gahyeonbot.core.speech.SpeechSegment> segments) {
        java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(segments.size());
        for (int position = 0; position < segments.size(); position++) {
            if (!healthy(request, currentGeneration)) return;
            var segment = segments.get(position);
            SessionTasks state = sessions.get(request.sessionId());
            if (state == null) return;
            String streamId = pcmStreams.start(
                    new ExpressiveSpeechRequest(segment, voice, request.expression()),
                    () -> healthy(request, currentGeneration),
                    () -> {
                        if (remaining.decrementAndGet() == 0) {
                            SessionTasks current = sessions.get(request.sessionId());
                            if (current != null && healthy(request, currentGeneration)) {
                                current.markSuccessful(request.generation(), request.utteranceIndex());
                            }
                        }
                    },
                    ignored -> {
                        if (currentGeneration.getAsBoolean()) failed(request, "tts_stream_failed");
                    });
            boolean finalSegment = position == segments.size() - 1;
            var preparedPayload = new java.util.LinkedHashMap<String, Object>();
            preparedPayload.put("generation", request.generation());
            preparedPayload.put("utteranceId", streamId);
            preparedPayload.put("utteranceIndex", request.utteranceIndex());
            preparedPayload.put("segmentIndex", segment.index());
            preparedPayload.put("segmentCount", segments.size());
            preparedPayload.put("finalSegment", finalSegment);
            preparedPayload.put("voiceProfile", voice.value());
            preparedPayload.put("voiceExpression", Map.of(
                    "style", request.expression().style(),
                    "intensity", request.expression().intensity(),
                    "communicativeIntent", request.expression().communicativeIntent()));
            preparedPayload.put("audio", Map.of(
                    "url", "/api/gahyeon/unreal/speech/stream/" + streamId,
                    "mimeType", "audio/pcm"));
            preparedPayload.put("visemes", List.of());
            int admittedRenderers = outbound.publishIf(
                    request.sessionId(), "speech.prepared", request.correlationId(),
                    Map.copyOf(preparedPayload),
                    boundedAdmission -> state.tryAdmitPublication(
                            request.generation(), currentGeneration, boundedAdmission));
            if (admittedRenderers <= 0) {
                pcmStreams.discard(streamId);
                if (admittedRenderers == 0 && healthy(request, currentGeneration)) {
                    failed(request, "tts_no_renderer");
                }
                return;
            }
            metrics.visemeTimeline("amplitude");
            if (position == 0) metrics.ttsFirstSegment(System.nanoTime() - requestedAt);
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
