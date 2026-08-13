package com.gahyeonbot.services.ai;

import com.gahyeonbot.application.conversation.ContentSafetyPort;
import com.gahyeonbot.core.conversation.ConversationAdmissionPolicy;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.repository.ModelUsageRepository;
import com.gahyeonbot.services.ai.agent.AgentModality;
import com.gahyeonbot.services.ai.agent.AgentExecutionControl;
import com.gahyeonbot.services.ai.agent.AgentRequest;
import com.gahyeonbot.services.ai.agent.AgentResult;
import com.gahyeonbot.services.ai.agent.AgentRuntime;
import com.gahyeonbot.services.ai.agent.AgentStreamCancelledException;
import com.gahyeonbot.services.ai.agent.AgentStreamObserver;
import com.gahyeonbot.entity.ModelUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationAdmissionServiceTest {
    @Test
    void providerUnsafeDecisionRejectsBeforeCognitionAndRecordsTheAttempt() {
        ModelUsageRepository usage = emptyUsage();
        AgentRuntime agent = mock(AgentRuntime.class);
        when(agent.isReady()).thenReturn(true);
        ContentSafetyPort safety = text -> ContentSafetyPort.Decision.UNSAFE;
        var service = service(usage, agent, safety);

        assertThatThrownBy(() -> service.chatResult(
                "request-1", "session-1", AgentModality.TEXT,
                new ActorId(42), "actor", null, "ordinary words"))
                .isInstanceOf(ConversationAdmissionService.AdversarialPromptException.class);

        verify(usage).saveAndFlush(any());
        verify(agent, never()).execute(any());
        verify(agent, never()).execute(any(), any());
    }

    @Test
    void unavailableProviderFallsBackToLocalPolicyWithoutBlockingCognition() throws Exception {
        ModelUsageRepository usage = emptyUsage();
        AgentRuntime agent = mock(AgentRuntime.class);
        when(agent.isReady()).thenReturn(true);
        when(agent.execute(any(), any())).thenReturn(new AgentResult(
                "run-1", "hello", List.of(), Duration.ofMillis(2)));
        ContentSafetyPort safety = text -> { throw new IllegalStateException("offline"); };
        var service = service(usage, agent, safety);

        assertThat(service.chatResult(
                "request-2", "session-2", AgentModality.TEXT,
                new ActorId(42), "actor", null, "hello").content()).isEqualTo("hello");

        verify(agent).execute(any(), any());
        verify(usage, org.mockito.Mockito.times(2)).saveAndFlush(any());
    }

    @Test
    void runtimeReadinessControlsAdmissionWithoutInspectingProviderCredentials() {
        ModelUsageRepository usage = emptyUsage();
        AgentRuntime unavailable = mock(AgentRuntime.class);
        when(unavailable.isReady()).thenReturn(false);
        var service = service(usage, unavailable, text -> ContentSafetyPort.Decision.SAFE);

        assertThat(service.isReady()).isFalse();
        assertThatThrownBy(() -> service.chatResult(
                "request-3", "session-3", AgentModality.TEXT,
                new ActorId(42), "actor", null, "hello"))
                .isInstanceOf(ConversationAdmissionService.RateLimitException.class)
                .hasMessageContaining("비활성화");
        verify(unavailable, never()).execute(any());
    }

    @Test
    void runtimeCanRecoverAndFailWithoutRestartingAdmission() throws Exception {
        ModelUsageRepository usage = emptyUsage();
        AgentRuntime runtime = mock(AgentRuntime.class);
        when(runtime.isReady()).thenReturn(false, true, false);
        when(runtime.execute(any(), any())).thenReturn(new AgentResult(
                "run-recovered", "recovered", List.of(), Duration.ZERO));
        var service = service(usage, runtime, text -> ContentSafetyPort.Decision.SAFE);

        assertThat(service.isReady()).isFalse();
        assertThat(service.chatResult(
                "request-4", "session-4", AgentModality.TEXT,
                new ActorId(42), "actor", null, "hello").content()).isEqualTo("recovered");
        assertThat(service.isReady()).isFalse();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void newerActorGenerationStartsWithoutWaitingForAnInterruptIgnoringProvider() throws Exception {
        Map<String, ModelUsage> records = new ConcurrentHashMap<>();
        ModelUsageRepository usage = emptyUsage();
        when(usage.saveAndFlush(any())).thenAnswer(invocation -> {
            ModelUsage value = invocation.getArgument(0);
            records.put(value.getRequestId(), value);
            return value;
        });
        BlockingRuntime runtime = new BlockingRuntime();
        var service = service(usage, runtime, text -> ContentSafetyPort.Decision.SAFE);
        var oldDeltas = new CopyOnWriteArrayList<String>();
        var newDeltas = new CopyOnWriteArrayList<String>();

        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var old = calls.submit(() -> service.chatResultStreaming(
                    "old-request", "old-session", AgentModality.TEXT,
                    new ActorId(42), "actor", null, "old prompt", oldDeltas::add));
            try {
                assertThat(runtime.oldEntered.await(1, TimeUnit.SECONDS)).isTrue();

                var replacement = calls.submit(() -> service.chatResultStreaming(
                        "new-request", "new-session", AgentModality.TEXT,
                        new ActorId(42), "actor", null, "new prompt", newDeltas::add));

                assertThat(replacement.get(1, TimeUnit.SECONDS).content()).isEqualTo("new response");
                assertThat(runtime.newEntered.getCount()).isZero();
                assertThat(old.isDone()).isFalse();
            } finally {
                runtime.releaseOld.countDown();
            }
            assertThatThrownBy(() -> old.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AgentStreamCancelledException.class);
        }

        assertThat(records.get("old-request").getSuccess()).isFalse();
        assertThat(records.get("old-request").getErrorMessage()).isEqualTo("CANCELLED");
        assertThat(records.get("old-request").getResponse()).isNull();
        assertThat(oldDeltas).isEmpty();
        assertThat(records.get("new-request").getSuccess()).isTrue();
        assertThat(records.get("new-request").getResponse()).isEqualTo("new response");
        assertThat(newDeltas).containsExactly("new delta");
    }

    private static ConversationAdmissionService service(
            ModelUsageRepository usage,
            AgentRuntime agent,
            ContentSafetyPort safety) {
        var service = new ConversationAdmissionService(
                usage, agent, new ConversationAdmissionPolicy(), safety, new SimpleMeterRegistry());
        return service;
    }

    private static ModelUsageRepository emptyUsage() {
        ModelUsageRepository usage = mock(ModelUsageRepository.class);
        when(usage.findDuplicatePrompt(any(), any(), any())).thenReturn(List.of());
        when(usage.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return usage;
    }

    private static final class BlockingRuntime implements AgentRuntime {
        private final CountDownLatch oldEntered = new CountDownLatch(1);
        private final CountDownLatch newEntered = new CountDownLatch(1);
        private final CountDownLatch releaseOld = new CountDownLatch(1);

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public AgentResult execute(AgentRequest request) {
            return execute(request, AgentExecutionControl.NONE);
        }

        @Override
        public AgentResult execute(AgentRequest request, AgentExecutionControl control) {
            return executeBlocking(request, null, control);
        }

        @Override
        public AgentResult executeStreaming(
                AgentRequest request,
                AgentStreamObserver observer,
                AgentExecutionControl control) {
            return executeBlocking(request, observer, control);
        }

        private AgentResult executeBlocking(
                AgentRequest request,
                AgentStreamObserver observer,
                AgentExecutionControl control) {
            control.onRunStarted("run-" + request.requestId(), () -> {});
            if (request.requestId().equals("old-request")) {
                oldEntered.countDown();
                while (releaseOld.getCount() > 0) {
                    try {
                        releaseOld.await();
                    } catch (InterruptedException ignored) {
                        // Deliberately model a provider that ignores interruption.
                    }
                }
                if (observer != null) observer.onTextDelta("stale delta");
                if (control.isCancelled()) throw new AgentStreamCancelledException();
                return new AgentResult("run-old", "stale response", List.of(), Duration.ZERO);
            }
            newEntered.countDown();
            if (observer != null) observer.onTextDelta("new delta");
            return new AgentResult("run-new", "new response", List.of(), Duration.ZERO);
        }

        @Override
        public AgentResult resume(String runId, ActorId actorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentResult resumeBackground(String runId, String backgroundResult) {
            throw new UnsupportedOperationException();
        }
    }
}
