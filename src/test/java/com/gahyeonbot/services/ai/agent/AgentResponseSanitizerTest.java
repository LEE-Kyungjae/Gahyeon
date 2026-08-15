package com.gahyeonbot.services.ai.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentResponseSanitizerTest {

    @Test
    void keepsOnlyFinalAnswerAfterThinkingBlock() {
        assertThat(DefaultAgentRuntime.sanitizeFinalResponse(
                "내부 추론과 미완성 답변</think>실제로 사용자에게 보낼 답변"))
                .isEqualTo("실제로 사용자에게 보낼 답변");
    }

    @Test
    void removesPairedThinkingBlock() {
        assertThat(DefaultAgentRuntime.sanitizeFinalResponse(
                "<think>숨겨야 할 추론</think>\n근거가 확인된 답변"))
                .isEqualTo("근거가 확인된 답변");
    }

    @Test
    void rejectsUnterminatedPlainTextReasoningLeak() {
        assertThat(DefaultAgentRuntime.sanitizeFinalResponse("""
                Here's a thinking process:

                1. Analyze User Input
                2. Draft Response
                "아니에요, 언제든 도와드릴게요."
                """))
                .isEmpty();
    }

    @Test
    void keepsOnlyExplicitFinalAnswerAfterPlainTextReasoning() {
        assertThat(DefaultAgentRuntime.sanitizeFinalResponse("""
                Here's a thinking process:
                internal reasoning

                Final Answer: 아니에요, 언제든 편하게 물어봐 주세요.
                """))
                .isEqualTo("아니에요, 언제든 편하게 물어봐 주세요.");
    }

    @Test
    void rejectsResponseContainingOnlyModelControlTokens() {
        assertThat(DefaultAgentRuntime.sanitizeFinalResponse(
                "<pad><pad><pad><pad>"))
                .isEmpty();
    }

    @Test
    void removesModelControlTokensAroundARealAnswer() {
        assertThat(DefaultAgentRuntime.sanitizeFinalResponse(
                "<s><pad>정상적인 답변입니다.</s>"))
                .isEqualTo("정상적인 답변입니다.");
    }

    @Test
    void keepsOnlyResponseFromThoughtResponseEnvelope() {
        assertThat(DefaultAgentRuntime.sanitizeFinalResponse("""
                <thought>
                internal reasoning that must never be shown
                thought>
                <response>
                사용자에게 보여 줄 최종 답변입니다.
                response>
                """))
                .isEqualTo("사용자에게 보여 줄 최종 답변입니다.");
    }
}
