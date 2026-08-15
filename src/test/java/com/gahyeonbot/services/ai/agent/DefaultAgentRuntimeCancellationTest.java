package com.gahyeonbot.services.ai.agent;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.memory.MemorySnapshot;
import com.gahyeonbot.core.memory.MemoryUseCase;
import com.gahyeonbot.core.tool.ToolPolicy;
import com.gahyeonbot.entity.AgentRun;
import com.gahyeonbot.repository.AgentRunRepository;
import com.gahyeonbot.services.ai.GitHubKnowledgeTools;
import com.gahyeonbot.services.ai.KnowledgeFreshnessTools;
import com.gahyeonbot.services.ai.PaperKnowledgeTools;
import com.gahyeonbot.services.ai.WeatherTools;
import com.gahyeonbot.services.weather.WeatherService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAgentRuntimeCancellationTest {
    @Test
    void retriesOnceWhenSanitizationRemovesTheEntireFinalResponse() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(
                        new AssistantMessage("analysis: internal reasoning only")))))
                .thenReturn(new ChatResponse(List.of(new Generation(
                        new AssistantMessage("정상적으로 복구한 답변입니다.")))));
        MemoryUseCase memory = emptyMemory();
        AgentRunLedger ledger = runningLedger("run-empty-final-retry");
        DefaultAgentRuntime runtime = runtime(
                model, memory, ledger, mock(AgentRunRepository.class),
                mock(AgentApprovalService.class), new ToolPolicy(),
                new WeatherTools(mock(WeatherService.class)));

        AgentResult result = runtime.execute(request("empty-final-retry"));

        assertThat(result.content()).isEqualTo("정상적으로 복구한 답변입니다.");
        verify(model, times(2)).call(any(Prompt.class));
        verify(ledger).succeed("run-empty-final-retry", "정상적으로 복구한 답변입니다.");
        verify(memory).remember(new ActorId(42), "prompt", "정상적으로 복구한 답변입니다.");
    }

    @Test
    void returnsSafeMessageWhenTheCorrectionRetryIsAlsoEmpty() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("analysis: internal reasoning only")))));
        MemoryUseCase memory = emptyMemory();
        AgentRunLedger ledger = runningLedger("run-empty-final-fallback");
        DefaultAgentRuntime runtime = runtime(
                model, memory, ledger, mock(AgentRunRepository.class),
                mock(AgentApprovalService.class), new ToolPolicy(),
                new WeatherTools(mock(WeatherService.class)));

        AgentResult result = runtime.execute(request("empty-final-fallback"));

        assertThat(result.content()).isEqualTo(
                "답변을 정리하는 중 문제가 생겼어요. 같은 질문을 한 번만 다시 말해 주세요.");
        verify(model, times(2)).call(any(Prompt.class));
        verify(ledger).succeed("run-empty-final-fallback", result.content());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void cancelledGenerationCannotCommitLedgerOrMemoryAfterProviderReturns() throws Exception {
        ChatModel model = mock(ChatModel.class);
        MemoryUseCase memory = mock(MemoryUseCase.class);
        when(memory.recall(any())).thenReturn(MemorySnapshot.EMPTY);
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRun run = AgentRun.builder()
                .id("run-old")
                .actorId(42L)
                .status(AgentRunStatus.QUEUED)
                .currentStep(0)
                .maxSteps(8)
                .createdAt(LocalDateTime.now())
                .build();
        when(ledger.create(any())).thenReturn(run);
        when(ledger.advanceStep(any(), any(), any())).thenReturn(run);
        var prompt = new AgentPromptProvider();
        prompt.load();
        var runtime = new DefaultAgentRuntime(
                model,
                memory,
                ledger,
                mock(AgentRunRepository.class),
                mock(AgentApprovalService.class),
                mock(ToolPolicy.class),
                prompt,
                new SimpleMeterRegistry(),
                mock(WeatherTools.class),
                mock(GitHubKnowledgeTools.class),
                mock(PaperKnowledgeTools.class),
                mock(KnowledgeFreshnessTools.class),
                new AgentRuntimeAvailability(5_000, System::nanoTime));
        var providerEntered = new CountDownLatch(1);
        var releaseProvider = new CountDownLatch(1);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            providerEntered.countDown();
            while (releaseProvider.getCount() > 0) {
                try {
                    releaseProvider.await();
                } catch (InterruptedException ignored) {
                    // Deliberately ignore cooperative thread interruption.
                }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("stale"))));
        });
        var cancelled = new AtomicBoolean();
        AgentExecutionControl control = cancelled::get;
        AgentRequest request = new AgentRequest(
                "old-request", "session", AgentModality.TEXT, null,
                new ActorId(42), "actor", "old prompt", 8);

        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = calls.submit(() -> runtime.execute(request, control));
            try {
                assertThat(providerEntered.await(10, TimeUnit.SECONDS)).isTrue();
                cancelled.set(true);
            } finally {
                releaseProvider.countDown();
            }

            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AgentStreamCancelledException.class);
        }

        verify(ledger, never()).succeed(any(), any());
        verify(memory, never()).remember(any(), any(), any());
        verify(ledger).cancel("run-old", new ActorId(42), "client_generation_changed");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void cancellationWinningBeforeSideEffectClaimPreventsToolInvocation() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(toolCall("get_current_weather",
                "{\"cityCode\":\"SEOUL\"}"));
        MemoryUseCase memory = emptyMemory();
        AgentRunLedger ledger = runningLedger("run-tool-before-claim");
        WeatherService weatherService = mock(WeatherService.class);
        DefaultAgentRuntime runtime = runtime(
                model, memory, ledger, mock(AgentRunRepository.class),
                mock(AgentApprovalService.class), new ToolPolicy(),
                new WeatherTools(weatherService));
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch claimEntered = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        AgentExecutionControl control = new AgentExecutionControl() {
            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public boolean tryStartSideEffect() {
                claimEntered.countDown();
                try {
                    releaseClaim.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return !cancelled.get();
            }
        };

        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = calls.submit(() -> runtime.execute(request("claim-race"), control));
            assertThat(claimEntered.await(2, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            releaseClaim.countDown();

            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AgentStreamCancelledException.class);
        }

        verify(weatherService, never()).buildCurrentWeatherMessage(any());
        verify(ledger, never()).appendToolEvent(
                eq("run-tool-before-claim"), eq(AgentEventType.TOOL_CALL_STARTED),
                any(), any());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void claimedToolMayFinishButCannotPublishPostCancellationEventsOrCommit() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(toolCall("get_current_weather",
                "{\"cityCode\":\"SEOUL\"}"));
        MemoryUseCase memory = emptyMemory();
        AgentRunLedger ledger = runningLedger("run-tool-in-flight");
        WeatherService weatherService = mock(WeatherService.class);
        CountDownLatch toolEntered = new CountDownLatch(1);
        CountDownLatch releaseTool = new CountDownLatch(1);
        when(weatherService.buildCurrentWeatherMessage(any())).thenAnswer(invocation -> {
            toolEntered.countDown();
            releaseTool.await();
            return "맑음";
        });
        DefaultAgentRuntime runtime = runtime(
                model, memory, ledger, mock(AgentRunRepository.class),
                mock(AgentApprovalService.class), new ToolPolicy(),
                new WeatherTools(weatherService));
        AtomicBoolean cancelled = new AtomicBoolean();

        try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = calls.submit(() -> runtime.execute(request("in-flight-race"), cancelled::get));
            assertThat(toolEntered.await(2, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            releaseTool.countDown();

            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AgentStreamCancelledException.class);
        }

        verify(weatherService).buildCurrentWeatherMessage(any());
        verify(ledger, never()).appendToolEvent(
                eq("run-tool-in-flight"), eq(AgentEventType.TOOL_CALL_COMPLETED),
                any(), any());
        verify(ledger, never()).succeed(any(), any());
        verify(memory, never()).remember(any(), any(), any());
    }

    @Test
    void approvalRequiredThatWasConcurrentlyCancelledSurfacesAsCancellation() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(toolCall("get_current_weather",
                "{\"cityCode\":\"SEOUL\"}"));
        MemoryUseCase memory = emptyMemory();
        AgentRunLedger ledger = runningLedger("run-approval-race");
        AgentApprovalService approvals = mock(AgentApprovalService.class);
        ToolPolicy policy = mock(ToolPolicy.class);
        when(policy.decide("get_current_weather")).thenReturn(com.gahyeonbot.core.tool.ToolDecision.REQUIRE_APPROVAL);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(approvals.request(any(), any(), any())).thenAnswer(invocation -> {
            cancelled.set(true);
            return com.gahyeonbot.entity.AgentApproval.builder().id("approval-1").build();
        });
        DefaultAgentRuntime runtime = runtime(
                model, memory, ledger, mock(AgentRunRepository.class), approvals, policy,
                new WeatherTools(mock(WeatherService.class)));

        assertThatThrownBy(() -> runtime.execute(request("approval-race"), cancelled::get))
                .isInstanceOf(AgentStreamCancelledException.class);
        verify(ledger).cancel("run-approval-race", new ActorId(42), "client_generation_changed");
    }

    @Test
    void cancelledApprovalRunCannotResume() {
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentRun cancelled = AgentRun.builder()
                .id("run-cancelled")
                .actorId(42L)
                .status(AgentRunStatus.CANCELLED)
                .build();
        when(runs.findByIdWithSession("run-cancelled")).thenReturn(Optional.of(cancelled));
        DefaultAgentRuntime runtime = runtime(
                mock(ChatModel.class), emptyMemory(), mock(AgentRunLedger.class), runs,
                mock(AgentApprovalService.class), new ToolPolicy(),
                new WeatherTools(mock(WeatherService.class)));

        assertThatThrownBy(() -> runtime.resume("run-cancelled", new ActorId(42)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED");
    }

    private static DefaultAgentRuntime runtime(
            ChatModel model,
            MemoryUseCase memory,
            AgentRunLedger ledger,
            AgentRunRepository runs,
            AgentApprovalService approvals,
            ToolPolicy policy,
            WeatherTools weatherTools) {
        var prompt = new AgentPromptProvider();
        prompt.load();
        return new DefaultAgentRuntime(
                model, memory, ledger, runs, approvals, policy, prompt,
                new SimpleMeterRegistry(), weatherTools,
                mock(GitHubKnowledgeTools.class), mock(PaperKnowledgeTools.class),
                mock(KnowledgeFreshnessTools.class),
                new AgentRuntimeAvailability(5_000, System::nanoTime));
    }

    private static MemoryUseCase emptyMemory() {
        MemoryUseCase memory = mock(MemoryUseCase.class);
        when(memory.recall(any())).thenReturn(MemorySnapshot.EMPTY);
        return memory;
    }

    private static AgentRunLedger runningLedger(String runId) {
        AgentRunLedger ledger = mock(AgentRunLedger.class);
        AgentRun run = AgentRun.builder()
                .id(runId)
                .actorId(42L)
                .status(AgentRunStatus.QUEUED)
                .currentStep(0)
                .maxSteps(8)
                .createdAt(LocalDateTime.now())
                .build();
        when(ledger.create(any())).thenReturn(run);
        when(ledger.advanceStep(any(), any(), any())).thenReturn(run);
        return ledger;
    }

    private static AgentRequest request(String requestId) {
        return new AgentRequest(
                requestId, "session", AgentModality.TEXT, null,
                new ActorId(42), "actor", "prompt", 8);
    }

    private static ChatResponse toolCall(String name, String arguments) {
        var toolCall = new AssistantMessage.ToolCall("call-1", "function", name, arguments);
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage("", Map.of(), List.of(toolCall)))));
    }
}
