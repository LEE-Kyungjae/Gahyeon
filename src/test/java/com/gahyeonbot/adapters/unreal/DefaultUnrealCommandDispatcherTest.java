package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.identity.IdentityResolutionUseCase;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.application.conversation.ConversationStreamingUseCase;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.identity.IdentityProvider;
import com.gahyeonbot.core.session.ClientSource;
import com.gahyeonbot.core.session.ConversationModality;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultUnrealCommandDispatcherTest {
    @Test
    void queuesCognitionAndPreservesUnrealSessionMetadata() {
        var tasks = new ArrayDeque<Runnable>();
        Executor executor = tasks::add;
        ConversationStreamingUseCase conversation = mock(ConversationStreamingUseCase.class);
        IdentityResolutionUseCase identities = mock(IdentityResolutionUseCase.class);
        when(identities.resolveExternal(IdentityProvider.UNREAL, "install-1", "테스터", null))
                .thenReturn(new ActorId(42));
        var captured = new AtomicReference<ConversationRequest>();
        when(conversation.converseStreaming(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return null;
        });
        var dispatcher = new DefaultUnrealCommandDispatcher(
                conversation, identities, executor, UnrealSpeechPreparationPort.NOOP);

        assertThat(dispatcher.dispatch(new UnrealConversationCommand(
                "unreal:g3:message-1", "session-1", "install-1", "테스터",
                ConversationModality.VOICE, 3, "안녕")))
                .isEqualTo(UnrealCommandDispatcher.DispatchResult.ACCEPTED);

        assertThat(captured.get()).isNull();
        assertThat(tasks).hasSize(1);
        tasks.remove().run();
        assertThat(captured.get().session().source()).isEqualTo(ClientSource.UNREAL);
        assertThat(captured.get().session().id().value()).isEqualTo("unreal:session-1");
        assertThat(captured.get().session().modality()).isEqualTo(ConversationModality.VOICE);
        assertThat(captured.get().session().clientContext()).containsEntry("unreal.generation", "3");
        verify(identities).resolveExternal(IdentityProvider.UNREAL, "install-1", "테스터", null);
    }

    @Test
    void dropsDuplicateAndQueuedStaleCommandsBeforeCognition() {
        var tasks = new ArrayDeque<Runnable>();
        ConversationStreamingUseCase conversation = mock(ConversationStreamingUseCase.class);
        IdentityResolutionUseCase identities = mock(IdentityResolutionUseCase.class);
        var dispatcher = new DefaultUnrealCommandDispatcher(
                conversation, identities, tasks::add, UnrealSpeechPreparationPort.NOOP);
        var old = new UnrealConversationCommand(
                "unreal:g1:old", "session-1", "install-1", "테스터",
                ConversationModality.TEXT, 1, "이전 질문");
        var current = new UnrealConversationCommand(
                "unreal:g2:new", "session-1", "install-1", "테스터",
                ConversationModality.TEXT, 2, "새 질문");

        assertThat(dispatcher.dispatch(old)).isEqualTo(UnrealCommandDispatcher.DispatchResult.ACCEPTED);
        assertThat(dispatcher.dispatch(old)).isEqualTo(UnrealCommandDispatcher.DispatchResult.DUPLICATE);
        assertThat(dispatcher.dispatch(current)).isEqualTo(UnrealCommandDispatcher.DispatchResult.ACCEPTED);
        assertThat(dispatcher.dispatch(new UnrealConversationCommand(
                "unreal:g0:stale", "session-1", "install-1", "테스터",
                ConversationModality.TEXT, 0, "너무 늦은 질문")))
                .isEqualTo(UnrealCommandDispatcher.DispatchResult.STALE);

        tasks.remove().run();
        verify(conversation, org.mockito.Mockito.never()).converseStreaming(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void admitsOnlyOneCognitionRequestPerGenerationEvenWhenMessageIdsDiffer() {
        var tasks = new ArrayDeque<Runnable>();
        var dispatcher = new DefaultUnrealCommandDispatcher(
                mock(ConversationStreamingUseCase.class),
                mock(IdentityResolutionUseCase.class),
                tasks::add,
                UnrealSpeechPreparationPort.NOOP);

        dispatcher.advanceGeneration("session-1", 8);
        assertThat(dispatcher.dispatch(new UnrealConversationCommand(
                "unreal:g8:first", "session-1", "install-1", "테스터",
                ConversationModality.VOICE, 8, "첫 요청")))
                .isEqualTo(UnrealCommandDispatcher.DispatchResult.ACCEPTED);
        assertThat(dispatcher.dispatch(new UnrealConversationCommand(
                "unreal:g8:second", "session-1", "install-1", "테스터",
                ConversationModality.VOICE, 8, "중복 요청")))
                .isEqualTo(UnrealCommandDispatcher.DispatchResult.DUPLICATE);
        assertThat(tasks).hasSize(1);
    }

    @Test
    void executorRejectionRollsBackGenerationAdmissionForRetry() {
        ConversationStreamingUseCase conversation = mock(ConversationStreamingUseCase.class);
        IdentityResolutionUseCase identities = mock(IdentityResolutionUseCase.class);
        AtomicReference<Runnable> accepted = new AtomicReference<>();
        AtomicReference<Boolean> reject = new AtomicReference<>(true);
        Executor executor = task -> {
            if (reject.get()) throw new java.util.concurrent.RejectedExecutionException("full");
            accepted.set(task);
        };
        var dispatcher = new DefaultUnrealCommandDispatcher(
                conversation, identities, executor, UnrealSpeechPreparationPort.NOOP);
        var first = new UnrealConversationCommand(
                "unreal:g9:first", "session-1", "install-1", "테스터",
                ConversationModality.TEXT, 9, "재시도할 요청");

        assertThat(dispatcher.dispatch(first))
                .isEqualTo(UnrealCommandDispatcher.DispatchResult.BACKPRESSURE);
        reject.set(false);
        assertThat(dispatcher.dispatch(new UnrealConversationCommand(
                "unreal:g9:retry", "session-1", "install-1", "테스터",
                ConversationModality.TEXT, 9, "재시도")))
                .isEqualTo(UnrealCommandDispatcher.DispatchResult.ACCEPTED);
        assertThat(accepted.get()).isNotNull();
    }

    @Test
    void handsLlmResponseToTtsWithoutBlockingCommandAdmission() {
        var cognitionTasks = new ArrayDeque<Runnable>();
        ConversationStreamingUseCase conversation = mock(ConversationStreamingUseCase.class);
        IdentityResolutionUseCase identities = mock(IdentityResolutionUseCase.class);
        when(identities.resolveExternal(IdentityProvider.UNREAL, "install-1", "테스터", null))
                .thenReturn(new ActorId(42));
        when(conversation.converseStreaming(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            com.gahyeonbot.application.conversation.ConversationStreamObserver observer = invocation.getArgument(1);
            observer.onTextDelta("합성할 응답.");
            var response = new ConversationResponse(
                    "run-1", "합성할 응답.", List.of(), Duration.ofMillis(20));
            observer.onCompleted(response);
            return response;
        });
        var speechRequest = new AtomicReference<UnrealSpeechPreparationRequest>();
        UnrealSpeechPreparationPort speech = (request, current) -> speechRequest.set(request);
        var dispatcher = new DefaultUnrealCommandDispatcher(
                conversation, identities, cognitionTasks::add, speech);

        assertThat(dispatcher.dispatch(new UnrealConversationCommand(
                "unreal:g5:message-5", "session-1", "install-1", "테스터",
                ConversationModality.TEXT, 5, "질문")))
                .isEqualTo(UnrealCommandDispatcher.DispatchResult.ACCEPTED);
        assertThat(speechRequest.get()).isNull();

        cognitionTasks.remove().run();
        assertThat(speechRequest.get().text()).isEqualTo("합성할 응답.");
        assertThat(speechRequest.get().generation()).isEqualTo(5);
    }

    @Test
    void newerGenerationInterruptsTheRunningStaleCognitionCall() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            ConversationStreamingUseCase conversation = mock(ConversationStreamingUseCase.class);
            IdentityResolutionUseCase identities = mock(IdentityResolutionUseCase.class);
            when(identities.resolveExternal(IdentityProvider.UNREAL, "install-1", "테스터", null))
                    .thenReturn(new ActorId(42));
            var started = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            when(conversation.converseStreaming(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        ConversationRequest request = invocation.getArgument(0);
                        if (request.requestId().contains("old")) {
                            started.countDown();
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException expected) {
                                interrupted.countDown();
                                Thread.currentThread().interrupt();
                            }
                        }
                        return null;
                    });
            var registry = new SimpleMeterRegistry();
            var dispatcher = new DefaultUnrealCommandDispatcher(
                    conversation, identities, executor, UnrealSpeechPreparationPort.NOOP,
                    new UnrealRuntimeMetrics(registry));

            dispatcher.dispatch(new UnrealConversationCommand(
                    "unreal:g1:old", "session-1", "install-1", "테스터",
                    ConversationModality.TEXT, 1, "이전 질문"));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            dispatcher.dispatch(new UnrealConversationCommand(
                    "unreal:g2:new", "session-1", "install-1", "테스터",
                    ConversationModality.TEXT, 2, "새 질문"));

            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.counter("gahyeon.unreal.cognition.cancelled").count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void newerGenerationUsesTheGrowthLaneWhenTheStaleProviderIgnoresInterruption()
            throws Exception {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(0);
        executor.initialize();
        var releaseOld = new CountDownLatch(1);
        try {
            ConversationStreamingUseCase conversation = mock(ConversationStreamingUseCase.class);
            IdentityResolutionUseCase identities = mock(IdentityResolutionUseCase.class);
            when(identities.resolveExternal(IdentityProvider.UNREAL, "install-1", "테스터", null))
                    .thenReturn(new ActorId(42));
            var oldEntered = new CountDownLatch(1);
            var replacementEntered = new CountDownLatch(1);
            when(conversation.converseStreaming(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        ConversationRequest request = invocation.getArgument(0);
                        if (request.requestId().contains("old")) {
                            oldEntered.countDown();
                            while (releaseOld.getCount() > 0) {
                                try {
                                    releaseOld.await();
                                } catch (InterruptedException ignored) {
                                    // Model a provider call that does not cooperate with interruption.
                                }
                            }
                        } else {
                            replacementEntered.countDown();
                        }
                        return null;
                    });
            var dispatcher = new DefaultUnrealCommandDispatcher(
                    conversation, identities, executor, UnrealSpeechPreparationPort.NOOP);

            assertThat(dispatcher.dispatch(command("old", "session-1", 1)))
                    .isEqualTo(UnrealCommandDispatcher.DispatchResult.ACCEPTED);
            assertThat(oldEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.dispatch(command("replacement", "session-1", 2)))
                    .isEqualTo(UnrealCommandDispatcher.DispatchResult.ACCEPTED);

            assertThat(replacementEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(releaseOld.getCount()).isEqualTo(1);
        } finally {
            releaseOld.countDown();
            executor.shutdown();
        }
    }

    @Test
    void generationAdvanceCancelsQueuedCognitionWithoutRequiringANewPrompt() {
        var tasks = new ArrayDeque<Runnable>();
        ConversationStreamingUseCase conversation = mock(ConversationStreamingUseCase.class);
        IdentityResolutionUseCase identities = mock(IdentityResolutionUseCase.class);
        var dispatcher = new DefaultUnrealCommandDispatcher(
                conversation, identities, tasks::add, UnrealSpeechPreparationPort.NOOP);
        dispatcher.dispatch(new UnrealConversationCommand(
                "unreal:g1:old", "session-1", "install-1", "테스터",
                ConversationModality.TEXT, 1, "오래된 질문"));

        dispatcher.advanceGeneration("session-1", 2);
        tasks.remove().run();

        verify(conversation, org.mockito.Mockito.never()).converseStreaming(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(dispatcher.dispatch(new UnrealConversationCommand(
                "unreal:g1:late", "session-1", "install-1", "테스터",
                ConversationModality.TEXT, 1, "늦은 질문")))
                .isEqualTo(UnrealCommandDispatcher.DispatchResult.STALE);
    }

    @Test
    void removesCancelledCognitionFromSpringQueueBeforeAdmittingReplacement() throws Exception {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.initialize();
        var releaseBlocker = new CountDownLatch(1);
        try {
            ConversationStreamingUseCase conversation = mock(ConversationStreamingUseCase.class);
            IdentityResolutionUseCase identities = mock(IdentityResolutionUseCase.class);
            when(identities.resolveExternal(
                    org.mockito.ArgumentMatchers.eq(IdentityProvider.UNREAL),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.isNull()))
                    .thenReturn(new ActorId(42));
            var blockerStarted = new CountDownLatch(1);
            var replacementRan = new CountDownLatch(1);
            var executed = java.util.Collections.synchronizedList(new ArrayList<String>());
            when(conversation.converseStreaming(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> {
                        ConversationRequest request = invocation.getArgument(0);
                        executed.add(request.requestId());
                        if (request.requestId().equals("blocker")) {
                            blockerStarted.countDown();
                            releaseBlocker.await();
                        } else if (request.requestId().equals("replacement")) {
                            replacementRan.countDown();
                        }
                        return null;
                    });
            var dispatcher = new DefaultUnrealCommandDispatcher(
                    conversation, identities, executor, UnrealSpeechPreparationPort.NOOP);

            dispatcher.dispatch(command("blocker", "other-session", 1));
            assertThat(blockerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            dispatcher.dispatch(command("stale-queued", "session-1", 1));
            assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);

            dispatcher.advanceGeneration("session-1", 2);
            assertThat(executor.getThreadPoolExecutor().getQueue()).isEmpty();
            dispatcher.dispatch(command("replacement", "session-1", 2));
            assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);
            releaseBlocker.countDown();

            assertThat(replacementRan.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(executed).containsExactly("blocker", "replacement");
        } finally {
            releaseBlocker.countDown();
            executor.shutdown();
        }
    }

    @Test
    void sessionReleaseCancelsQueuedCognitionAndAllowsCleanReconnect() {
        var tasks = new ArrayDeque<Runnable>();
        ConversationStreamingUseCase conversation = mock(ConversationStreamingUseCase.class);
        IdentityResolutionUseCase identities = mock(IdentityResolutionUseCase.class);
        var dispatcher = new DefaultUnrealCommandDispatcher(
                conversation, identities, tasks::add, UnrealSpeechPreparationPort.NOOP);
        var command = new UnrealConversationCommand(
                "unreal:g4:old", "session-1", "install-1", "테스터",
                ConversationModality.TEXT, 4, "연결이 끊길 질문");
        assertThat(dispatcher.dispatch(command))
                .isEqualTo(UnrealCommandDispatcher.DispatchResult.ACCEPTED);

        dispatcher.releaseSession("session-1");
        tasks.remove().run();
        verify(conversation, org.mockito.Mockito.never()).converseStreaming(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        assertThat(dispatcher.dispatch(new UnrealConversationCommand(
                "unreal:g4:reconnected", "session-1", "install-1", "테스터",
                ConversationModality.TEXT, 4, "재연결 질문")))
                .isEqualTo(UnrealCommandDispatcher.DispatchResult.ACCEPTED);
    }

    private static UnrealConversationCommand command(
            String requestId, String sessionId, long generation) {
        return new UnrealConversationCommand(
                requestId, sessionId, "install-1", "테스터",
                ConversationModality.TEXT, generation, "질문");
    }
}
