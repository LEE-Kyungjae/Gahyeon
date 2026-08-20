package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.speech.AudioOutput;
import com.gahyeonbot.core.speech.SpeechSegment;
import com.gahyeonbot.core.speech.SpeechSynthesisUseCase;
import com.gahyeonbot.core.speech.VoiceProfileId;
import com.gahyeonbot.core.speech.VoiceExpression;
import com.gahyeonbot.core.speech.ExpressiveSpeechRequest;
import com.gahyeonbot.core.speech.ExpressiveSpeechSynthesisUseCase;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class DefaultUnrealSpeechPreparationServiceTest {
    @Test
    void preservesExpressiveVoiceThroughAudioAndVisemePublication() {
        var tasks = new ArrayDeque<Runnable>();
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        var expressive = mock(ExpressiveSpeechSynthesisUseCase.class);
        var segment = new SpeechSegment(0, "정말 온 거야?");
        var expression = new VoiceExpression("surprised", 0.8, "reunion");
        when(synthesis.prepare(segment.text())).thenReturn(List.of(segment));
        when(expressive.isExpressiveReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
        when(expressive.synthesizeExpressive(new ExpressiveSpeechRequest(
                segment, VoiceProfileId.ASSISTANT, expression))).thenReturn(pcmWav());
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        var messages = new ArrayList<com.gahyeonbot.adapters.unreal.protocol.UnrealEnvelope>();
        broker.subscribe("connection-1", "session-1", messages::add);
        var service = new DefaultUnrealSpeechPreparationService(
                synthesis, expressive,
                new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5)),
                broker, tasks::add,
                new UnrealRuntimeMetrics(new SimpleMeterRegistry()),
                (text, audio) -> List.of(new UnrealVisemeCue("aa", 0, 20, 1)));

        service.prepare(new UnrealSpeechPreparationRequest(
                "session-1", "cognition:g1", 1, 0, segment.text(),
                VoiceProfileId.ASSISTANT, expression), () -> true);
        tasks.remove().run();

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().payload()).containsEntry("voiceProfile", "gahyeon.assistant");
        Map<?, ?> voiceExpression = (Map<?, ?>) messages.getFirst().payload().get("voiceExpression");
        assertThat(voiceExpression.get("style")).isEqualTo("surprised");
        assertThat(voiceExpression.get("intensity")).isEqualTo(0.8);
        assertThat((List<?>) messages.getFirst().payload().get("visemes")).hasSize(1);
        verify(expressive).synthesizeExpressive(
                new ExpressiveSpeechRequest(segment, VoiceProfileId.ASSISTANT, expression));
    }

    @Test
    void queuesTtsAndPublishesEachSegmentAsSoonAsItIsReady() {
        var tasks = new ArrayDeque<Runnable>();
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
        var segments = List.of(new SpeechSegment(0, "첫 문장"), new SpeechSegment(1, "둘째 문장"));
        when(synthesis.prepare("첫 문장. 둘째 문장.")).thenReturn(segments);
        when(synthesis.synthesize(segments.get(0), VoiceProfileId.ASSISTANT))
                .thenReturn(pcmWav());
        when(synthesis.synthesize(segments.get(1), VoiceProfileId.ASSISTANT))
                .thenReturn(pcmWav());
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        var messages = new ArrayList<com.gahyeonbot.adapters.unreal.protocol.UnrealEnvelope>();
        broker.subscribe("connection-1", "session-1", messages::add);
        var cache = new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5));
        var registry = new SimpleMeterRegistry();
        var service = new DefaultUnrealSpeechPreparationService(
                synthesis, cache, broker, tasks::add, new UnrealRuntimeMetrics(registry),
                (text, audio) -> List.of(
                        new UnrealVisemeCue("aa", 0, 90, 1.0),
                        new UnrealVisemeCue("ih", 70, 80, 0.8)));

        service.prepare(new UnrealSpeechPreparationRequest(
                "session-1", "unreal:g2:message-1:sentence:0", 2, 0,
                "첫 문장. 둘째 문장."), () -> true);
        service.finishSequence(new UnrealSpeechSequenceEndRequest(
                "session-1", "unreal:g2:message-1", 2, 1, "completed"), () -> true);

        assertThat(messages).isEmpty();
        assertThat(tasks).hasSize(1);
        tasks.remove().run();
        tasks.remove().run();
        assertThat(messages).extracting(message -> message.type())
                .containsExactly("speech.prepared", "speech.prepared", "speech.sequence.ended");
        assertThat(messages.getFirst().payload()).containsEntry("segmentIndex", 0);
        assertThat(messages.getFirst().payload()).containsEntry("utteranceIndex", 0);
        assertThat((List<?>) messages.getFirst().payload().get("visemes"))
                .hasSize(2)
                .first()
                .isEqualTo(java.util.Map.of(
                        "semantic", "aa",
                        "atMs", 0L,
                        "durationMs", 90L,
                        "weight", 1.0));
        assertThat(messages.get(1).payload()).containsEntry("finalSegment", true);
        assertThat(messages.getLast().payload())
                .containsEntry("utteranceCount", 1)
                .containsEntry("outcome", "completed");
        String audioId = (String) messages.getFirst().payload().get("utteranceId");
        assertThat(cache.get(audioId)).isPresent();
        assertThat(messages.getFirst().payload().get("audio")).isEqualTo(java.util.Map.of(
                "url", "/api/gahyeon/unreal/speech/audio/" + audioId,
                "mimeType", "audio/wav"));
        assertThat(registry.get("gahyeon.unreal.tts.first.segment").timer().count()).isEqualTo(1);
        assertThat(registry.get("gahyeon.unreal.tts.segment").timer().count()).isEqualTo(2);
        assertThat(registry.counter(
                "gahyeon.unreal.viseme.timeline", "source", "provider").count())
                .isEqualTo(2);
        assertThat(registry.get("gahyeon.unreal.viseme.alignment")
                .tags("source", "provider", "result", "success").timer().count())
                .isEqualTo(2);
    }

    @Test
    void discardsAudioWhenGenerationChangesDuringSynthesis() {
        var tasks = new ArrayDeque<Runnable>();
        var current = new AtomicBoolean(true);
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        var segment = new SpeechSegment(0, "이전 응답");
        when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
        when(synthesis.prepare("이전 응답")).thenReturn(List.of(segment));
        when(synthesis.synthesize(segment, VoiceProfileId.ASSISTANT)).thenAnswer(invocation -> {
            current.set(false);
            return pcmWav();
        });
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        var messages = new ArrayList<String>();
        broker.subscribe("connection-1", "session-1", envelope -> messages.add(envelope.type()));
        var service = new DefaultUnrealSpeechPreparationService(
                synthesis,
                new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5)),
                broker,
                tasks::add,
                new UnrealRuntimeMetrics(new SimpleMeterRegistry()));

        service.prepare(new UnrealSpeechPreparationRequest(
                "session-1", "unreal:g1:message-1", 1, "이전 응답"), current::get);
        tasks.remove().run();

        assertThat(messages).isEmpty();
    }

    @Test
    void rejectsNonWavProviderOutputBeforePublishingUnplayableSpeech() {
        var tasks = new ArrayDeque<Runnable>();
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        var segment = new SpeechSegment(0, "MP3 fallback");
        when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
        when(synthesis.prepare("MP3 fallback")).thenReturn(List.of(segment));
        when(synthesis.synthesize(segment, VoiceProfileId.ASSISTANT))
                .thenReturn(new AudioOutput(new byte[]{1, 2, 3}, "audio/mpeg", "mp3"));
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        var messages = new ArrayList<com.gahyeonbot.adapters.unreal.protocol.UnrealEnvelope>();
        broker.subscribe("connection-1", "session-1", messages::add);
        var cache = new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5));
        var service = new DefaultUnrealSpeechPreparationService(
                synthesis, cache, broker, tasks::add,
                new UnrealRuntimeMetrics(new SimpleMeterRegistry()));

        service.prepare(new UnrealSpeechPreparationRequest(
                "session-1", "unreal:g1:mp3", 1, "MP3 fallback"), () -> true);
        service.finishSequence(new UnrealSpeechSequenceEndRequest(
                "session-1", "unreal:g1:mp3", 1, 1, "completed"), () -> true);
        tasks.remove().run();
        tasks.remove().run();

        assertThat(messages).extracting(event -> event.type())
                .containsExactly("speech.sequence.ended");
        assertThat(messages.getFirst().payload())
                .containsEntry("outcome", "failed")
                .containsEntry("utteranceCount", 0);
        assertThat(cache.entryCount()).isZero();
    }

    @Test
    void sequenceFailureReportsOnlyContiguousSuccessfulUtterancesAndSkipsLaterTts() {
        var tasks = new ArrayDeque<Runnable>();
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        var first = new SpeechSegment(0, "첫 문장");
        var failed = new SpeechSegment(0, "실패 문장");
        when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
        when(synthesis.prepare("첫 문장")).thenReturn(List.of(first));
        when(synthesis.prepare("실패 문장")).thenReturn(List.of(failed));
        when(synthesis.synthesize(first, VoiceProfileId.ASSISTANT))
                .thenReturn(pcmWav());
        when(synthesis.synthesize(failed, VoiceProfileId.ASSISTANT))
                .thenReturn(new AudioOutput(new byte[]{2}, "audio/mpeg", "mp3"));
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        var messages = new ArrayList<com.gahyeonbot.adapters.unreal.protocol.UnrealEnvelope>();
        broker.subscribe("connection-1", "session-1", messages::add);
        var service = new DefaultUnrealSpeechPreparationService(
                synthesis,
                new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5)),
                broker,
                tasks::add,
                new UnrealRuntimeMetrics(new SimpleMeterRegistry()),
                (text, audio) -> List.of());

        service.prepare(new UnrealSpeechPreparationRequest(
                "session-1", "unreal:g3:first", 3, 0, "첫 문장"), () -> true);
        service.prepare(new UnrealSpeechPreparationRequest(
                "session-1", "unreal:g3:failed", 3, 1, "실패 문장"), () -> true);
        service.prepare(new UnrealSpeechPreparationRequest(
                "session-1", "unreal:g3:skipped", 3, 2, "건너뛸 문장"), () -> true);
        service.finishSequence(new UnrealSpeechSequenceEndRequest(
                "session-1", "unreal:g3:root", 3, 3, "completed"), () -> true);
        while (!tasks.isEmpty()) tasks.remove().run();

        assertThat(messages).extracting(event -> event.type())
                .containsExactly("speech.prepared", "speech.sequence.ended");
        assertThat(messages.getLast().payload())
                .containsEntry("outcome", "failed")
                .containsEntry("utteranceCount", 1);
        org.mockito.Mockito.verify(synthesis, org.mockito.Mockito.never())
                .prepare("건너뛸 문장");
    }

    @Test
    void generationAdvanceCancelsQueuedTtsBeforeProviderWorkStarts() {
        var tasks = new ArrayDeque<Runnable>();
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        var registry = new SimpleMeterRegistry();
        var service = new DefaultUnrealSpeechPreparationService(
                synthesis,
                new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5)),
                broker,
                tasks::add,
                new UnrealRuntimeMetrics(registry));

        service.advanceGeneration("session-1", 1);
        service.prepare(new UnrealSpeechPreparationRequest(
                "session-1", "unreal:g1:message", 1, "취소될 응답"), () -> true);
        service.advanceGeneration("session-1", 2);
        tasks.remove().run();

        org.mockito.Mockito.verifyNoInteractions(synthesis);
        assertThat(registry.counter("gahyeon.unreal.tts.cancelled").count()).isEqualTo(1);
    }

    @Test
    void generationAdvanceInterruptsRunningTtsFuture() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var started = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
            when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
            var segment = new SpeechSegment(0, "긴 합성");
            when(synthesis.prepare("긴 합성")).thenReturn(List.of(segment));
            when(synthesis.synthesize(segment, VoiceProfileId.ASSISTANT)).thenAnswer(invocation -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cancelled", expected);
                }
                throw new AssertionError("unreachable");
            });
            var service = new DefaultUnrealSpeechPreparationService(
                    synthesis,
                    new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5)),
                    new UnrealEphemeralBroker(Clock.systemUTC()),
                    executor,
                    new UnrealRuntimeMetrics(new SimpleMeterRegistry()));
            var current = new AtomicBoolean(true);

            service.advanceGeneration("session-1", 1);
            service.prepare(new UnrealSpeechPreparationRequest(
                    "session-1", "unreal:g1:message", 1, "긴 합성"), current::get);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            current.set(false);
            service.advanceGeneration("session-1", 2);

            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void serializesUtterancesWithinSessionWhileKeepingPoolConcurrencyAvailable() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
            when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
            var first = new SpeechSegment(0, "첫 문장");
            var second = new SpeechSegment(0, "둘째 문장");
            var other = new SpeechSegment(0, "다른 세션");
            when(synthesis.prepare(first.text())).thenReturn(List.of(first));
            when(synthesis.prepare(second.text())).thenReturn(List.of(second));
            when(synthesis.prepare(other.text())).thenReturn(List.of(other));
            var firstStarted = new CountDownLatch(1);
            var releaseFirst = new CountDownLatch(1);
            var secondStarted = new CountDownLatch(1);
            var otherStarted = new CountDownLatch(1);
            when(synthesis.synthesize(first, VoiceProfileId.ASSISTANT)).thenAnswer(invocation -> {
                firstStarted.countDown();
                releaseFirst.await();
                return pcmWav();
            });
            when(synthesis.synthesize(second, VoiceProfileId.ASSISTANT)).thenAnswer(invocation -> {
                secondStarted.countDown();
                return pcmWav();
            });
            when(synthesis.synthesize(other, VoiceProfileId.ASSISTANT)).thenAnswer(invocation -> {
                otherStarted.countDown();
                return pcmWav();
            });
            var messages = java.util.Collections.synchronizedList(
                    new ArrayList<com.gahyeonbot.adapters.unreal.protocol.UnrealEnvelope>());
            var broker = new UnrealEphemeralBroker(Clock.systemUTC());
            broker.subscribe("renderer", "session-1", messages::add);
            broker.subscribe("other-renderer", "session-2", ignored -> {});
            var service = new DefaultUnrealSpeechPreparationService(
                    synthesis,
                    new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5)),
                    broker, executor, new UnrealRuntimeMetrics(new SimpleMeterRegistry()));

            service.prepare(new UnrealSpeechPreparationRequest(
                    "session-1", "first", 1, 0, first.text()), () -> true);
            service.prepare(new UnrealSpeechPreparationRequest(
                    "session-1", "second", 1, 1, second.text()), () -> true);
            service.prepare(new UnrealSpeechPreparationRequest(
                    "session-2", "other", 1, 0, other.text()), () -> true);

            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(otherStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(secondStarted.await(150, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirst.countDown();
            assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (messages.size() < 2 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(messages).extracting(event -> event.payload().get("utteranceIndex"))
                    .containsExactly(0, 1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void boundsThePerSessionSerialQueue() {
        var tasks = new ArrayDeque<Runnable>();
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        var registry = new SimpleMeterRegistry();
        var messages = new ArrayList<com.gahyeonbot.adapters.unreal.protocol.UnrealEnvelope>();
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        broker.subscribe("renderer", "session-1", messages::add);
        var service = new DefaultUnrealSpeechPreparationService(
                synthesis,
                new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5)),
                broker,
                tasks::add,
                new UnrealRuntimeMetrics(registry));

        for (int index = 0; index < 65; index++) {
            service.prepare(new UnrealSpeechPreparationRequest(
                    "session-1", "utterance-" + index, 1, index, "문장 " + index), () -> true);
        }
        service.finishSequence(new UnrealSpeechSequenceEndRequest(
                "session-1", "sequence", 1, 65, "completed"), () -> true);

        assertThat(tasks).hasSize(1);
        assertThat(registry.counter(
                "gahyeon.unreal.tts.failures", "code", "tts_queue_full").count())
                .isEqualTo(1);
        while (!tasks.isEmpty()) tasks.remove().run();
        assertThat(messages).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("speech.sequence.ended");
            assertThat(event.payload()).containsEntry("outcome", "failed");
        });
    }

    @Test
    void lastSubscriberFailureDoesNotSelfInterruptTtsCleanup() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
            var segment = new SpeechSegment(0, "전송 실패");
            when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
            when(synthesis.prepare("전송 실패")).thenReturn(List.of(segment));
            when(synthesis.synthesize(segment, VoiceProfileId.ASSISTANT)).thenReturn(pcmWav());
            var broker = new UnrealEphemeralBroker(Clock.systemUTC());
            var serviceRef = new AtomicReference<DefaultUnrealSpeechPreparationService>();
            var interruptedInsideCleanup = new AtomicBoolean(true);
            var cleaned = new CountDownLatch(1);
            broker.subscribe("failed-renderer", "session-1", ignored -> {
                throw new IllegalStateException("socket failed");
            }, () -> {
                serviceRef.get().releaseSession("session-1");
                interruptedInsideCleanup.set(Thread.currentThread().isInterrupted());
                cleaned.countDown();
            });
            var service = new DefaultUnrealSpeechPreparationService(
                    synthesis,
                    new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5)),
                    broker,
                    executor,
                    new UnrealRuntimeMetrics(new SimpleMeterRegistry()));
            serviceRef.set(service);

            service.prepare(new UnrealSpeechPreparationRequest(
                    "session-1", "unreal:g1:failed-send", 1, "전송 실패"), () -> true);

            assertThat(cleaned.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(interruptedInsideCleanup.get()).isFalse();
            assertThat(broker.subscriberCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void generationAdvanceCancelsInternalSessionQueueWithoutFillingExecutorQueue() throws Exception {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(8);
        executor.initialize();
        try {
            var started = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
            var segment = new SpeechSegment(0, "실행 중");
            when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
            when(synthesis.prepare("실행 중")).thenReturn(List.of(segment));
            when(synthesis.synthesize(segment, VoiceProfileId.ASSISTANT)).thenAnswer(invocation -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cancelled", expected);
                }
                throw new AssertionError("unreachable");
            });
            var service = new DefaultUnrealSpeechPreparationService(
                    synthesis,
                    new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5)),
                    new UnrealEphemeralBroker(Clock.systemUTC()),
                    executor,
                    new UnrealRuntimeMetrics(new SimpleMeterRegistry()));
            var current = new AtomicBoolean(true);

            service.advanceGeneration("session-1", 1);
            service.prepare(new UnrealSpeechPreparationRequest(
                    "session-1", "unreal:g1:running", 1, "실행 중"), current::get);
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            service.prepare(new UnrealSpeechPreparationRequest(
                    "session-1", "unreal:g1:queued", 1, "대기 중"), current::get);
            assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();

            current.set(false);
            service.advanceGeneration("session-1", 2);

            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void discardsCachedAudioWhenGenerationChangesDuringAlignment() {
        var tasks = new ArrayDeque<Runnable>();
        var current = new AtomicBoolean(true);
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        var segment = new SpeechSegment(0, "정렬 중 취소");
        when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
        when(synthesis.prepare("정렬 중 취소")).thenReturn(List.of(segment));
        var output = pcmWav();
        when(synthesis.synthesize(segment, VoiceProfileId.ASSISTANT)).thenReturn(output);
        var cache = new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5));
        var messages = new ArrayList<String>();
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        broker.subscribe("connection-1", "session-1", event -> messages.add(event.type()));
        var service = new DefaultUnrealSpeechPreparationService(
                synthesis, cache, broker, tasks::add,
                new UnrealRuntimeMetrics(new SimpleMeterRegistry()),
                (text, audio) -> {
                    current.set(false);
                    return List.of();
                });

        service.prepare(new UnrealSpeechPreparationRequest(
                "session-1", "unreal:g1:message", 1, "정렬 중 취소"), current::get);
        tasks.remove().run();

        assertThat(messages).isEmpty();
        assertThat(cache.entryCount()).isZero();
    }

    @Test
    void externalGenerationAdvanceAfterLastHealthCheckRejectsQueueAdmission() {
        var tasks = new ArrayDeque<Runnable>();
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        var segment = new SpeechSegment(0, "게시 입장 직전 취소");
        when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
        when(synthesis.prepare(segment.text())).thenReturn(List.of(segment));
        when(synthesis.synthesize(segment, VoiceProfileId.ASSISTANT)).thenReturn(pcmWav());
        var cache = new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5));
        var messages = new ArrayList<String>();
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        broker.subscribe("connection-1", "session-1", event -> messages.add(event.type()));
        var externalCurrent = new AtomicBoolean(true);
        var advancedInGap = new AtomicBoolean();
        var registry = new SimpleMeterRegistry();
        var service = new DefaultUnrealSpeechPreparationService(
                synthesis, cache, broker, tasks::add,
                new UnrealRuntimeMetrics(registry));
        service.advanceGeneration("session-1", 1);

        service.prepare(new UnrealSpeechPreparationRequest(
                "session-1", "unreal:g1:message", 1, segment.text()), () -> {
                    if (cache.entryCount() == 1 && advancedInGap.compareAndSet(false, true)) {
                        externalCurrent.set(false);
                        return true;
                    }
                    return externalCurrent.get();
                });
        tasks.remove().run();

        assertThat(advancedInGap).isTrue();
        assertThat(messages).isEmpty();
        assertThat(cache.entryCount()).isZero();
        assertThat(registry.counter(
                "gahyeon.unreal.tts.failures", "code", "tts_no_renderer").count())
                .isZero();
    }

    @Test
    void rejectsProviderVisemesOutsideTheAudioTimeline() {
        var tasks = new ArrayDeque<Runnable>();
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        var segment = new SpeechSegment(0, "범위를 벗어난 정렬");
        when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
        when(synthesis.prepare(segment.text())).thenReturn(List.of(segment));
        when(synthesis.synthesize(segment, VoiceProfileId.ASSISTANT)).thenReturn(pcmWav());
        var registry = new SimpleMeterRegistry();
        var messages = new ArrayList<com.gahyeonbot.adapters.unreal.protocol.UnrealEnvelope>();
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        broker.subscribe("connection-1", "session-1", messages::add);
        var service = new DefaultUnrealSpeechPreparationService(
                synthesis,
                new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5)),
                broker,
                tasks::add,
                new UnrealRuntimeMetrics(registry),
                (text, audio) -> List.of(new UnrealVisemeCue("aa", 950, 100, 1.0)));

        service.prepare(new UnrealSpeechPreparationRequest(
                "session-1", "unreal:g1:message", 1, segment.text()), () -> true);
        tasks.remove().run();

        assertThat((List<?>) messages.getFirst().payload().get("visemes")).isEmpty();
        assertThat(registry.counter(
                "gahyeon.unreal.viseme.timeline", "source", "amplitude").count())
                .isEqualTo(1);
        assertThat(registry.get("gahyeon.unreal.viseme.alignment")
                .tags("source", "provider", "result", "contract_invalid").timer().count())
                .isEqualTo(1);
    }

    @Test
    void measuresProviderFailureSeparatelyFromAmplitudeFallback() {
        var tasks = new ArrayDeque<Runnable>();
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        var segment = new SpeechSegment(0, "정렬 실패 fallback");
        when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
        when(synthesis.prepare(segment.text())).thenReturn(List.of(segment));
        when(synthesis.synthesize(segment, VoiceProfileId.ASSISTANT)).thenReturn(pcmWav());
        var registry = new SimpleMeterRegistry();
        var messages = new ArrayList<com.gahyeonbot.adapters.unreal.protocol.UnrealEnvelope>();
        var broker = new UnrealEphemeralBroker(Clock.systemUTC());
        broker.subscribe("connection-1", "session-1", messages::add);
        var service = new DefaultUnrealSpeechPreparationService(
                synthesis,
                new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5)),
                broker,
                tasks::add,
                new UnrealRuntimeMetrics(registry),
                (text, audio) -> { throw new IllegalStateException("aligner unavailable"); });

        service.prepare(new UnrealSpeechPreparationRequest(
                "session-1", "unreal:g1:message", 1, segment.text()), () -> true);
        tasks.remove().run();

        assertThat((List<?>) messages.getFirst().payload().get("visemes")).isEmpty();
        assertThat(registry.get("gahyeon.unreal.viseme.alignment")
                .tags("source", "provider", "result", "failure").timer().count())
                .isEqualTo(1);
        assertThat(registry.counter(
                "gahyeon.unreal.viseme.timeline", "source", "amplitude").count())
                .isEqualTo(1);
    }

    @Test
    void discardsPreparedAudioWhenNoRendererCanAcceptIt() {
        var tasks = new ArrayDeque<Runnable>();
        SpeechSynthesisUseCase synthesis = mock(SpeechSynthesisUseCase.class);
        var segment = new SpeechSegment(0, "연결이 끊긴 응답");
        when(synthesis.isReady(VoiceProfileId.ASSISTANT)).thenReturn(true);
        when(synthesis.prepare(segment.text())).thenReturn(List.of(segment));
        when(synthesis.synthesize(segment, VoiceProfileId.ASSISTANT)).thenReturn(pcmWav());
        var cache = new UnrealAudioCache(Clock.systemUTC(), Duration.ofMinutes(5));
        var registry = new SimpleMeterRegistry();
        var service = new DefaultUnrealSpeechPreparationService(
                synthesis, cache, new UnrealEphemeralBroker(Clock.systemUTC()),
                tasks::add, new UnrealRuntimeMetrics(registry));

        service.prepare(new UnrealSpeechPreparationRequest(
                "session-1", "unreal:g1:no-renderer", 1, 0, segment.text()), () -> true);
        service.finishSequence(new UnrealSpeechSequenceEndRequest(
                "session-1", "unreal:g1:root", 1, 1, "completed"), () -> true);
        tasks.remove().run();
        tasks.remove().run();

        assertThat(cache.entryCount()).isZero();
        assertThat(registry.counter(
                "gahyeon.unreal.tts.failures", "code", "tts_no_renderer").count())
                .isEqualTo(1);
    }

    private static AudioOutput pcmWav() {
        int sampleRate = 16_000;
        int frames = 16_000;
        int dataBytes = frames * 2;
        ByteBuffer bytes = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        bytes.putInt(36 + dataBytes);
        bytes.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        bytes.putInt(16);
        bytes.putShort((short) 1);
        bytes.putShort((short) 1);
        bytes.putInt(sampleRate);
        bytes.putInt(sampleRate * 2);
        bytes.putShort((short) 2);
        bytes.putShort((short) 16);
        bytes.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        bytes.putInt(dataBytes);
        return new AudioOutput(bytes.array(), "audio/wav", "wav");
    }
}
