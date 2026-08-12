package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.conversation.ConversationStreamObserver;
import com.gahyeonbot.core.conversation.ConversationResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class UnrealStreamingSpeechObserver implements ConversationStreamObserver {
    private static final int MAXIMUM_UTTERANCES = 64;
    private final String sessionId;
    private final String correlationId;
    private final long generation;
    private final UnrealSpeechPreparationPort speech;
    private final BooleanSupplier currentGeneration;
    private final IncrementalSentenceAccumulator sentences;
    private final AtomicInteger sentenceIndex = new AtomicInteger();
    private final UnrealRuntimeMetrics metrics;
    private final long admittedAtNanos;
    private final AtomicBoolean firstDelta = new AtomicBoolean();
    private final AtomicBoolean firstSentence = new AtomicBoolean();
    private final AtomicBoolean ended = new AtomicBoolean();

    public UnrealStreamingSpeechObserver(
            String sessionId,
            String correlationId,
            long generation,
            UnrealSpeechPreparationPort speech,
            BooleanSupplier currentGeneration,
            int maxSentenceCharacters) {
        this(sessionId, correlationId, generation, speech, currentGeneration,
                maxSentenceCharacters, null, System.nanoTime());
    }

    public UnrealStreamingSpeechObserver(
            String sessionId,
            String correlationId,
            long generation,
            UnrealSpeechPreparationPort speech,
            BooleanSupplier currentGeneration,
            int maxSentenceCharacters,
            UnrealRuntimeMetrics metrics,
            long admittedAtNanos) {
        this.sessionId = sessionId;
        this.correlationId = correlationId;
        this.generation = generation;
        this.speech = speech;
        this.currentGeneration = currentGeneration;
        this.sentences = new IncrementalSentenceAccumulator(maxSentenceCharacters);
        this.metrics = metrics;
        this.admittedAtNanos = admittedAtNanos;
    }

    @Override
    public synchronized void onTextDelta(String delta) {
        if (ended.get()) return;
        if (delta != null && !delta.isEmpty() && firstDelta.compareAndSet(false, true) && metrics != null) {
            metrics.cognitionFirstDelta(System.nanoTime() - admittedAtNanos);
        }
        dispatch(sentences.accept(delta));
    }

    @Override
    public synchronized void onCompleted(ConversationResponse response) {
        if (ended.get()) return;
        dispatch(sentences.finish());
        finish("completed");
    }

    @Override
    public synchronized void onFailed(RuntimeException failure) {
        finish("failed");
    }

    @Override
    public boolean isCancelled() {
        return !currentGeneration.getAsBoolean();
    }

    private void dispatch(List<String> ready) {
        for (String sentence : ready) {
            if (!currentGeneration.getAsBoolean()) return;
            if (sentenceIndex.get() >= MAXIMUM_UTTERANCES) {
                finish("failed");
                return;
            }
            if (firstSentence.compareAndSet(false, true) && metrics != null) {
                metrics.cognitionFirstSentence(System.nanoTime() - admittedAtNanos);
            }
            int index = sentenceIndex.getAndIncrement();
            speech.prepare(new UnrealSpeechPreparationRequest(
                    sessionId,
                    correlationId + ":sentence:" + index,
                    generation,
                    index,
                    sentence), currentGeneration);
        }
    }

    private void finish(String outcome) {
        if (!ended.compareAndSet(false, true) || !currentGeneration.getAsBoolean()) return;
        speech.finishSequence(new UnrealSpeechSequenceEndRequest(
                sessionId, correlationId, generation, sentenceIndex.get(), outcome),
                currentGeneration);
    }
}
