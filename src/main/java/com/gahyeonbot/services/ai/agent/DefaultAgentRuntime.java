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
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultAgentRuntime implements AgentRuntime {
    private static final int REPEATED_TOOL_CALL_LIMIT = 3;
    private static final int MAX_EVENT_PAYLOAD = 4_000;

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

    @Override
    public AgentResult execute(AgentRequest request) {
        long startedNanos = System.nanoTime();
        AgentRun run = ledger.create(request.toRunRequest());
        if (run.getStatus() == AgentRunStatus.SUCCEEDED) {
            return new AgentResult(run.getId(), run.getOutputText(), List.of(), Duration.ZERO);
        }
        if (run.getStatus() != AgentRunStatus.QUEUED) {
            throw new AgentExecutionException(
                    run.getId(), "RUN_NOT_ADMISSIBLE",
                    "이미 처리 중이거나 종료된 요청입니다: " + run.getStatus(), null);
        }

        ledger.transition(run.getId(), AgentRunStatus.RUNNING, AgentEventType.RUN_STARTED, null);
        return runLoop(request, run, startedNanos, null);
    }

    @Override
    public AgentResult resume(String runId, long actorUserId) {
        AgentRun run = runRepository.findByIdWithSession(runId)
                .orElseThrow(() -> new IllegalArgumentException("실행을 찾을 수 없습니다: " + runId));
        if (run.getUserId() != actorUserId) {
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
                run.getGateway(),
                run.getGuildId(),
                run.getUserId(),
                run.getUsername(),
                run.getInputText(),
                run.getMaxSteps());
        ledger.transition(runId, AgentRunStatus.RUNNING, AgentEventType.RUN_RESUMED, "approval");
        return runLoop(request, run, System.nanoTime(), null);
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
                run.getGateway(),
                run.getGuildId(),
                run.getUserId(),
                run.getUsername(),
                run.getInputText(),
                run.getMaxSteps());
        ledger.transition(runId, AgentRunStatus.RUNNING,
                AgentEventType.BACKGROUND_RESULT_RECEIVED, limited(backgroundResult));
        return runLoop(request, run, System.nanoTime(), backgroundResult);
    }

    private AgentResult runLoop(
            AgentRequest request,
            AgentRun run,
            long startedNanos,
            String backgroundResult) {
        List<String> usedTools = new ArrayList<>();
        try {
            MemorySnapshot memory = loadMemory(request.userId());
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

            while (true) {
                AgentRun stepped = ledger.advanceStep(
                        run.getId(), AgentEventType.MODEL_CALL_STARTED, null);
                ChatResponse response = chatModel.call(new Prompt(messages, options));
                AssistantMessage assistant = response.getResult().getOutput();
                ledger.appendToolEvent(
                        run.getId(), AgentEventType.MODEL_CALL_COMPLETED, null,
                        "toolCalls=" + assistant.getToolCalls().size());
                messages.add(assistant);

                if (!assistant.hasToolCalls()) {
                    String content = sanitizeFinalResponse(assistant.getText());
                    if (content.isBlank()) throw new IllegalStateException("모델의 최종 응답이 비어 있습니다.");
                    ledger.succeed(run.getId(), content);
                    memoryUseCase.remember(new ActorId(request.userId()), request.message(), content);
                    Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
                    recordMetrics(request.gateway(), "succeeded", duration);
                    return new AgentResult(run.getId(), content, usedTools, duration);
                }

                List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
                for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
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
            recordMetrics(request.gateway(), "waiting_approval",
                    Duration.ofNanos(System.nanoTime() - startedNanos));
            throw approval;
        } catch (Exception failure) {
            String errorCode = failure instanceof AgentRunLedger.StepLimitExceededException
                    ? "STEP_LIMIT_EXCEEDED"
                    : "AGENT_EXECUTION_FAILED";
            try {
                ledger.fail(run.getId(), errorCode, limited(failure.getMessage()));
            } catch (Exception ledgerFailure) {
                log.error("에이전트 실패 원장 기록 실패 run={}", run.getId(), ledgerFailure);
            }
            Duration duration = Duration.ofNanos(System.nanoTime() - startedNanos);
            recordMetrics(request.gateway(), "failed", duration);
            throw new AgentExecutionException(
                    run.getId(), errorCode, "에이전트 실행에 실패했습니다.", failure);
        }
    }

    private MemorySnapshot loadMemory(Long userId) {
        try {
            return memoryUseCase.recall(new ActorId(userId));
        } catch (Exception e) {
            log.warn("에이전트 메모리 로드 실패 user={}", userId, e);
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
                [gateway]
                %s

                [응답 매체 지침]
                %s

                [현재 시각]
                %s

                [현재 질문]
                %s
                """.formatted(
                request.gateway(),
                gatewayGuidance(request.gateway()),
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

    private static String gatewayGuidance(AgentGateway gateway) {
        return switch (gateway) {
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

    private void recordMetrics(AgentGateway gateway, String status, Duration duration) {
        meterRegistry.counter("gahyeonbot.agent.runs",
                "gateway", gateway.name().toLowerCase(Locale.ROOT),
                "status", status).increment();
        Timer.builder("gahyeonbot.agent.run.duration")
                .tag("gateway", gateway.name().toLowerCase(Locale.ROOT))
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
        int thinkingEnd = content.toLowerCase(Locale.ROOT).lastIndexOf("</think>");
        if (thinkingEnd >= 0) {
            String finalAnswer = content.substring(thinkingEnd + "</think>".length()).trim();
            if (!finalAnswer.isBlank()) content = finalAnswer;
        }
        return content
                .replaceAll("(?is)<think>.*?</think>", "")
                .replaceAll("(?is)</?think>", "")
                .trim();
    }
}
