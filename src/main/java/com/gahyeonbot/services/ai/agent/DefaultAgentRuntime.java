package com.gahyeonbot.services.ai.agent;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.memory.MemoryRole;
import com.gahyeonbot.core.memory.MemorySnapshot;
import com.gahyeonbot.core.memory.MemoryUseCase;
import com.gahyeonbot.core.tool.ToolDecision;
import com.gahyeonbot.core.tool.ToolPolicy;
import com.gahyeonbot.entity.AgentRun;
import com.gahyeonbot.repository.AgentRunRepository;
import com.gahyeonbot.services.ai.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
@RequiredArgsConstructor
@Slf4j
public class DefaultAgentRuntime implements AgentRuntime {
    private static final int REPEATED_TOOL_CALL_LIMIT = 3;
    private static final int MAX_EVENT_PAYLOAD = 4_000;
    private static final int EMPTY_FINAL_RESPONSE_RETRY_LIMIT = 1;
    private static final String EMPTY_FINAL_RESPONSE_FALLBACK =
            "답변을 정리하는 중 문제가 생겼어요. 같은 질문을 한 번만 다시 말해 주세요.";

    private final ChatModel chatModel;
    private final MemoryUseCase memoryUseCase;
    private final AgentRunLedger ledger;
    private final AgentRunRepository runRepository;
    private final AgentApprovalService approvalService;
    private final ToolPolicy toolPolicy;
    private final AgentPromptProvider promptProvider;
    private final MeterRegistry meterRegistry;
    private final WeatherTools weatherTools;
    private final GitHubKnowledgeTools gitHubKnowledgeTools;
    private final PaperKnowledgeTools paperKnowledgeTools;
    private final KnowledgeFreshnessTools knowledgeFreshnessTools;
    private final AgentRuntimeAvailability availability;

    @Value("${gahyeon.agent.streaming.tool-text-exclusive-enabled:false}")
    private boolean toolTextExclusiveStreamingEnabled;
    @Value("${spring.ai.openai.base-url:}")
    private String configuredBaseUrl;
    @Value("${spring.ai.openai.chat.options.model:}")
    private String configuredModel;
    @Value("${gahyeon.agent.streaming.verified-base-url:}")
    private String verifiedStreamingBaseUrl;
    @Value("${gahyeon.agent.streaming.verified-model:}")
    private String verifiedStreamingModel;
    private final AtomicBoolean streamingContractHealthy = new AtomicBoolean(true);

    @Override
    public boolean isReady() {
        return availability.isReady();
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        return execute(request, null, AgentExecutionControl.NONE);
    }

    @Override
    public AgentResult execute(AgentRequest request, AgentExecutionControl control) {
        return execute(request, null, Objects.requireNonNull(control, "execution control"));
    }

    @Override
    public AgentResult executeStreaming(AgentRequest request, AgentStreamObserver observer) {
        return execute(
                request,
                Objects.requireNonNull(observer, "stream observer"),
                AgentExecutionControl.NONE);
    }

    @Override
    public AgentResult executeStreaming(
            AgentRequest request,
            AgentStreamObserver observer,
            AgentExecutionControl control) {
        AgentStreamObserver delegate = Objects.requireNonNull(observer, "stream observer");
        AgentExecutionControl executionControl = Objects.requireNonNull(control, "execution control");
        AgentStreamObserver guarded = new AgentStreamObserver() {
            @Override
            public void onTextDelta(String delta) {
                if (isCancelled()) throw new AgentStreamCancelledException();
                delegate.onTextDelta(delta);
            }

            @Override
            public boolean isCancelled() {
                return executionControl.isCancelled() || delegate.isCancelled();
            }
        };
        return execute(
                request,
                guarded,
                executionControl);
    }

    private AgentResult execute(
            AgentRequest request,
            AgentStreamObserver observer,
            AgentExecutionControl control) {
        long startedNanos = System.nanoTime();
        ensureNotCancelled(observer, control);
        AgentRun run = ledger.create(request.toRunRequest());
        if (run.getStatus() == AgentRunStatus.SUCCEEDED) {
            ensureNotCancelled(observer, control);
            return new AgentResult(run.getId(), run.getOutputText(), List.of(), Duration.ZERO);
        }
        if (run.getStatus() != AgentRunStatus.QUEUED) {
            throw new AgentExecutionException(
                    run.getId(), "RUN_NOT_ADMISSIBLE",
                    "이미 처리 중이거나 종료된 요청입니다: " + run.getStatus(), null);
        }

        ledger.startInteractiveRun(
                run.getId(), request.actorId(), "client_generation_changed");
        control.onRunStarted(
                run.getId(),
                () -> cancelRun(run.getId(), request.actorId(), "client_generation_changed"));
        ensureNotCancelled(observer, control);
        return runLoop(request, run, startedNanos, null, observer, control);
    }

    @Override
    public AgentResult resume(String runId, ActorId actorId) {
        AgentRun run = runRepository.findByIdWithSession(runId)
                .orElseThrow(() -> new IllegalArgumentException("실행을 찾을 수 없습니다: " + runId));
        if (run.getActorId() != actorId.value()) {
            throw new SecurityException("이 실행을 재개할 권한이 없습니다.");
        }
        if (run.getStatus() != AgentRunStatus.WAITING_APPROVAL) {
            throw new IllegalStateException("승인 대기 실행만 재개할 수 있습니다: " + run.getStatus());
        }
        if (!approvalService.hasApproved(runId)) {
            throw new IllegalStateException("승인된 도구 호출이 없습니다.");
        }
        AgentRequest request = new AgentRequest(
                run.getRequestId(),
                run.getSession().getSessionKey(),
                run.getModality(),
                run.getToolScopeId(),
                new ActorId(run.getActorId()),
                run.getActorDisplayName(),
                run.getInputText(),
                run.getMaxSteps());
        ledger.claimResume(
                runId,
                AgentRunStatus.WAITING_APPROVAL,
                AgentEventType.RUN_RESUMED,
                "approval");
        return runLoop(
                request, run, System.nanoTime(), null, null, AgentExecutionControl.NONE);
    }

    @Override
    public AgentResult resumeBackground(String runId, String backgroundResult) {
        AgentRun run = runRepository.findByIdWithSession(runId)
                .orElseThrow(() -> new IllegalArgumentException("실행을 찾을 수 없습니다: " + runId));
        if (run.getStatus() != AgentRunStatus.WAITING_BACKGROUND) {
            throw new IllegalStateException("백그라운드 대기 실행만 재개할 수 있습니다: " + run.getStatus());
        }
        AgentRequest request = new AgentRequest(
                run.getRequestId(),
                run.getSession().getSessionKey(),
                run.getModality(),
                run.getToolScopeId(),
                new ActorId(run.getActorId()),
                run.getActorDisplayName(),
                run.getInputText(),
                run.getMaxSteps());
        ledger.claimResume(
                runId,
                AgentRunStatus.WAITING_BACKGROUND,
                AgentEventType.BACKGROUND_RESULT_RECEIVED,
                limited(backgroundResult));
        return runLoop(
                request, run, System.nanoTime(), backgroundResult, null,
                AgentExecutionControl.NONE);
    }

    private AgentResult runLoop(
            AgentRequest request,
            AgentRun run,
            long startedNanos,
            String backgroundResult,
            AgentStreamObserver streamObserver,
            AgentExecutionControl control) {
        List<String> usedTools = new ArrayList<>();
        try {
            MemorySnapshot memory = loadMemory(request.actorId());
            List<Message> messages = initialMessages(request, memory, backgroundResult);
            ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(weatherTools, gitHubKnowledgeTools, paperKnowledgeTools, knowledgeFreshnessTools)
                    .build()
                    .getToolCallbacks();
            Map<String, ToolCallback> callbackByName = new LinkedHashMap<>();
            for (ToolCallback callback : callbacks) {
                callbackByName.put(callback.getToolDefinition().name(), callback);
            }
            var options = DefaultToolCallingChatOptions.builder()
                    .toolCallbacks(callbacks)
                    .internalToolExecutionEnabled(false)
                    .build();
            AgentLoopGuard loopGuard = new AgentLoopGuard(REPEATED_TOOL_CALL_LIMIT);
            int emptyFinalResponseRetries = 0;

            while (true) {
                ensureNotCancelled(streamObserver, control);
                AgentRun stepped = ledger.advanceStep(
                        run.getId(), AgentEventType.MODEL_CALL_STARTED, null);
                if (!availability.tryAcquireProviderCall()) {
                    meterRegistry.counter("gahyeonbot.agent.provider.circuit.rejections").increment();
                    throw new AgentProviderUnavailableException();
                }
                ChatResponse response;
                try {
                    response = new ToolSafeChatStreamer(
                            chatModel,
                            StreamingModelVerification.allows(
                                    toolTextExclusiveStreamingEnabled,
                                    configuredBaseUrl,
                                    configuredModel,
                                    verifiedStreamingBaseUrl,
                                    verifiedStreamingModel)
                                    && streamingContractHealthy.get())
                            .call(new Prompt(messages, options), streamObserver);
                } catch (ToolStreamingContractViolationException violation) {
                    availability.recordProviderSuccess();
                    streamingContractHealthy.set(false);
                    meterRegistry.counter("gahyeonbot.agent.streaming.contract.violations").increment();
                    log.error("모델 streaming tool/text 배타 계약 위반; 이후 요청은 동기 fallback 사용", violation);
                    throw violation;
                } catch (ModelProviderException providerFailure) {
                    if (cancelled(streamObserver, control)) {
                        availability.recordProviderSuccess();
                        throw new AgentStreamCancelledException(providerFailure);
                    }
                    availability.recordProviderFailure();
                    meterRegistry.counter("gahyeonbot.agent.provider.failures").increment();
                    throw providerFailure;
                } catch (AgentStreamCancelledException
                         | AgentStreamObserverDeliveryException nonProviderFailure) {
                    availability.recordProviderSuccess();
                    throw nonProviderFailure;
                }
                availability.recordProviderSuccess();
                ensureNotCancelled(streamObserver, control);
                AssistantMessage assistant = response.getResult().getOutput();
                ledger.appendToolEvent(
                        run.getId(), AgentEventType.MODEL_CALL_COMPLETED, null,
                        "toolCalls=" + assistant.getToolCalls().size());
                messages.add(assistant);

                if (!assistant.hasToolCalls()) {
                    String content = sanitizeFinalResponse(assistant.getText());
                    if (content.isBlank()
                            && emptyFinalResponseRetries < EMPTY_FINAL_RESPONSE_RETRY_LIMIT
                            && stepped.getCurrentStep() < request.maxSteps()) {
                        emptyFinalResponseRetries++;
                        meterRegistry.counter("gahyeonbot.agent.empty_final_response.retries").increment();
                        log.warn("빈 최종 응답 교정 재시도 run={} retry={}",
                                run.getId(), emptyFinalResponseRetries);
                        messages.add(new UserMessage("""
                                [출력 교정]
                                내부 추론이나 태그를 출력하지 말고, 사용자의 질문에 대한 최종 답변만 한국어로 작성해.
                                """));
                        continue;
                    }
                    if (content.isBlank()) {
                        meterRegistry.counter("gahyeonbot.agent.empty_final_response.fallbacks").increment();
                        log.error("빈 최종 응답 교정 실패; 안전 응답 사용 run={}", run.getId());
                        content = EMPTY_FINAL_RESPONSE_FALLBACK;
                    }
                    String finalContent = content;
                    boolean committed = control.commitIfActive(() -> {
                        ensureNotCancelled(streamObserver, control);
                        ledger.succeed(run.getId(), finalContent);
                        memoryUseCase.remember(request.actorId(), request.message(), finalContent);
                    });
                    if (!committed) throw new AgentStreamCancelledException();
                    Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
                    recordMetrics(request.modality(), "succeeded", duration);
                    return new AgentResult(run.getId(), finalContent, usedTools, duration);
                }

                List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
                for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                    ensureNotCancelled(streamObserver, control);
                    loopGuard.recordToolCall(toolCall.name(), toolCall.arguments());
                    ToolCallback callback = callbackByName.get(toolCall.name());
                    if (callback == null) {
                        throw new IllegalStateException("등록되지 않은 도구입니다: " + toolCall.name());
                    }
                    ToolDecision decision = toolPolicy.decide(toolCall.name());
                    ledger.appendToolEvent(run.getId(), AgentEventType.TOOL_CALL_REQUESTED,
                            toolCall.name(), limited(toolCall.arguments()));
                    if (decision == ToolDecision.DENY) {
                        throw new IllegalStateException("정책상 허용되지 않은 도구입니다: " + toolCall.name());
                    }
                    if (decision == ToolDecision.REQUIRE_APPROVAL) {
                        if (approvalService.consumeIfApproved(
                                run.getId(), toolCall.name(), toolCall.arguments())) {
                            ledger.appendToolEvent(run.getId(), AgentEventType.APPROVAL_CONSUMED,
                                    toolCall.name(), limited(toolCall.arguments()));
                        } else {
                            var approval = approvalService.request(
                                    run.getId(), toolCall.name(), toolCall.arguments());
                            ledger.transition(run.getId(), AgentRunStatus.WAITING_APPROVAL,
                                    AgentEventType.APPROVAL_REQUESTED, approval.getId());
                            throw new AgentApprovalRequiredException(
                                    run.getId(), toolCall.name(), toolCall.arguments());
                        }
                    }

                    if (!control.tryStartSideEffect()) {
                        throw new AgentStreamCancelledException();
                    }
                    ledger.appendToolEvent(
                            run.getId(), AgentEventType.TOOL_CALL_STARTED, toolCall.name(), null);
                    String toolResult;
                    try {
                        toolResult = callback.call(toolCall.arguments());
                    } catch (Exception toolFailure) {
                        ledger.appendToolEvent(run.getId(), AgentEventType.TOOL_CALL_FAILED,
                                toolCall.name(), limited(toolFailure.getMessage()));
                        throw toolFailure;
                    }
                    ensureNotCancelled(streamObserver, control);
                    ledger.appendToolEvent(run.getId(), AgentEventType.TOOL_CALL_COMPLETED,
                            toolCall.name(), limited(toolResult));
                    usedTools.add(toolCall.name());
                    toolResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(), toolResult));
                }
                messages.add(new ToolResponseMessage(toolResponses));

                if (stepped.getCurrentStep() >= request.maxSteps()) {
                    throw new AgentRunLedger.StepLimitExceededException(run.getId(), request.maxSteps());
                }
            }
        } catch (AgentApprovalRequiredException approval) {
            if (cancelled(streamObserver, control)) {
                cancelRun(run.getId(), request.actorId(), "client_generation_changed");
                recordMetrics(request.modality(), "cancelled",
                        Duration.ofNanos(System.nanoTime() - startedNanos));
                throw new AgentStreamCancelledException(approval);
            }
            recordMetrics(request.modality(), "waiting_approval",
                    Duration.ofNanos(System.nanoTime() - startedNanos));
            throw approval;
        } catch (Exception failure) {
            if (cancelled(streamObserver, control)) {
                cancelRun(run.getId(), request.actorId(), "client_generation_changed");
                Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
                recordMetrics(request.modality(), "cancelled", duration);
                if (failure instanceof AgentStreamCancelledException cancelled) throw cancelled;
                throw new AgentStreamCancelledException(failure);
            }
            String errorCode = failure instanceof AgentRunLedger.StepLimitExceededException
                    ? "STEP_LIMIT_EXCEEDED"
                    : "AGENT_EXECUTION_FAILED";
            try {
                ledger.fail(run.getId(), errorCode, limited(failure.getMessage()));
            } catch (Exception ledgerFailure) {
                log.error("에이전트 실패 원장 기록 실패 run={}", run.getId(), ledgerFailure);
            }
            Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
            recordMetrics(request.modality(), "failed", duration);
            throw new AgentExecutionException(
                    run.getId(), errorCode, "에이전트 실행에 실패했습니다.", failure);
        }
    }

    private void ensureNotCancelled(
            AgentStreamObserver observer,
            AgentExecutionControl control) {
        if (cancelled(observer, control)) throw new AgentStreamCancelledException();
    }

    private boolean cancelled(
            AgentStreamObserver observer,
            AgentExecutionControl control) {
        return control.isCancelled()
                || observer != null
                && (observer.isCancelled() || Thread.currentThread().isInterrupted());
    }

    private void cancelRun(String runId, ActorId actorId, String reason) {
        try {
            ledger.cancel(runId, actorId, reason);
        } catch (Exception ledgerFailure) {
            log.error("에이전트 취소 원장 기록 실패 run={}", runId, ledgerFailure);
        }
    }

    private MemorySnapshot loadMemory(ActorId actorId) {
        try {
            return memoryUseCase.recall(actorId);
        } catch (Exception e) {
            log.warn("에이전트 메모리 로드 실패 actor={}", actorId.value(), e);
            return MemorySnapshot.EMPTY;
        }
    }

    private List<Message> initialMessages(
            AgentRequest request,
            MemorySnapshot memory,
            String backgroundResult) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptProvider.systemPrompt(memory.summary())));
        memory.recentMessages().forEach(message -> messages.add(
                message.role() == MemoryRole.USER
                        ? new UserMessage(message.content())
                        : new AssistantMessage(message.content())));
        messages.add(new UserMessage("""
                [modality]
                %s

                [응답 매체 지침]
                %s

                [현재 시각]
                %s

                [현재 질문]
                %s
                """.formatted(
                request.modality(),
                modalityGuidance(request.modality()),
                ZonedDateTime.now(ZoneId.of("Asia/Seoul")),
                request.message())));
        if (backgroundResult != null && !backgroundResult.isBlank()) {
            messages.add(new UserMessage("""
                    [백그라운드 작업 완료 결과]
                    %s

                    위 결과를 반영해 원래 요청에 대한 최종 답변을 작성해.
                    """.formatted(backgroundResult)));
        }
        return messages;
    }

    private static String modalityGuidance(AgentModality modality) {
        return switch (modality) {
            case VOICE -> """
                    음성으로 듣기 편한 문장으로 답한다. 기본은 핵심부터 2~4문장으로 말하되,
                    사용자가 설명·비교·방법·논문 내용을 요구하면 이해에 필요한 만큼 충분히 설명한다.
                    마크다운, 이모지, URL 낭독, 표, 장식용 특수문자는 사용하지 않는다.
                    """;
            case TEXT -> """
                    텍스트로 읽기 좋은 답변을 작성한다. 단순 질문은 짧게, 기술·논문·분석 질문은
                    결론과 근거가 빠지지 않도록 필요한 만큼 자세히 답한다. 길이를 캐릭터성 때문에 줄이지 않는다.
                    """;
            case SYSTEM -> "업무 목적과 전달 대상에 맞춰 간결성과 완전성을 조절한다.";
        };
    }

    private void recordMetrics(AgentModality modality, String status, Duration duration) {
        meterRegistry.counter("gahyeonbot.agent.runs",
                "modality", modality.name().toLowerCase(Locale.ROOT),
                "status", status).increment();
        Timer.builder("gahyeonbot.agent.run.duration")
                .tag("modality", modality.name().toLowerCase(Locale.ROOT))
                .tag("status", status)
                .register(meterRegistry)
                .record(duration);
    }

    private static String limited(String value) {
        if (value == null) return null;
        return value.length() <= MAX_EVENT_PAYLOAD
                ? value
                : value.substring(0, MAX_EVENT_PAYLOAD) + "...[truncated]";
    }

    static String sanitizeFinalResponse(String value) {
        if (value == null || value.isBlank()) return "";
        String content = value.trim();
        String lowerContent = content.toLowerCase(Locale.ROOT);
        int responseStart = lowerContent.lastIndexOf("<response>");
        if (responseStart >= 0) {
            String explicitResponse = content.substring(responseStart + "<response>".length())
                    .replaceFirst("(?is)\\s*(?:</response>|response>)\\s*$", "")
                    .trim();
            if (!explicitResponse.isBlank()) content = explicitResponse;
        }
        int thinkingEnd = content.toLowerCase(Locale.ROOT).lastIndexOf("</think>");
        if (thinkingEnd >= 0) {
            String finalAnswer = content.substring(thinkingEnd + "</think>".length()).trim();
            if (!finalAnswer.isBlank()) content = finalAnswer;
        }
        content = content
                .replaceAll("(?is)<think>.*?</think>", "")
                .replaceAll("(?is)</?think>", "")
                .replaceAll("(?is)<thought>.*?(?:</thought>|thought>)", "")
                .replaceAll("(?is)</?thought>", "")
                .replaceAll("(?is)</?response>", "")
                .replaceAll("(?i)(?:<pad>|<unk>|<s>|</s>)+", "")
                .trim();
        String lower = content.toLowerCase(Locale.ROOT);
        for (String marker : List.of(
                "\nfinal answer:", "\nfinal response:",
                "\n최종 답변:", "\n최종 응답:")) {
            int markerIndex = lower.lastIndexOf(marker);
            if (markerIndex >= 0) {
                String finalAnswer = content.substring(markerIndex + marker.length()).trim();
                if (!finalAnswer.isBlank()) return finalAnswer;
            }
        }
        if (lower.startsWith("here's a thinking process:")
                || lower.startsWith("here is a thinking process:")
                || lower.startsWith("thinking process:")
                || lower.startsWith("analysis:")
                || lower.startsWith("<thought>")) {
            return "";
        }
        return content;
    }
}
