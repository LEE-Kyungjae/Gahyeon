package com.gahyeonbot.adapters.life;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.life.*;
import com.gahyeonbot.core.life.CharacterMemory;
import com.gahyeonbot.core.life.ExpressionPlan;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
public final class OpenAiCharacterCognitionAdapter implements CharacterCognitionPort {
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resources;

    public OpenAiCharacterCognitionAdapter(ChatModel chatModel, ObjectMapper objectMapper, ResourceLoader resources) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.resources = resources;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public CharacterCognitionResult generate(CharacterCognitionRequest request) {
        String persona = loadPersona(request.character().personaPrompt());
        String system = persona + """

                너는 사용자의 질문에 응답하는 채팅 세션이 아니라 자율 생활 판단을 수행 중이다.
                관찰되지 않은 사건을 지어내지 말고, 말할 이유가 약하면 speak=false를 선택한다.
                숨겨진 사고 과정은 출력하지 않는다. 아래 JSON 객체 하나만 출력한다.
                {"speak":false,"utterance":null,"memoryNote":"짧은 관찰 또는 null","memoryImportance":0.0,
                 "voiceStyle":"natural","intensity":0.3,"facialExpression":"neutral",
                 "gazeTarget":"user","gesture":"none","resumePreviousActivity":true}
                utterance는 speak=true일 때만 한두 문장의 자연스러운 한국어로 작성한다.
                memoryNote에는 사실·약속·관계 변화 같은 나중에 유용한 정보만 짧게 기록한다.
                """;
        String user = """
                [자율 판단 이유] %s
                [현재 활동] %s
                [현재 목표] %s
                [욕구] social=%.3f curiosity=%.3f rest=%.3f
                [정서] valence=%.3f arousal=%.3f
                [주의 대상] %s
                [미완료 약속] %s
                [최근 캐릭터 전용 기억]
                %s
                [제안된 표현] intent=%s voice=%s intensity=%.3f face=%s gaze=%s gesture=%s
                """.formatted(
                request.reason(), request.state().activity(), request.state().currentGoal(),
                request.state().socialNeed(), request.state().curiosityNeed(), request.state().restNeed(),
                request.state().valence(), request.state().arousal(), nullable(request.state().attentionTarget()),
                nullable(request.state().prospectiveIntention()), memories(request.recentMemories()),
                request.proposedExpression().communicativeIntent(), request.proposedExpression().voiceStyle(),
                request.proposedExpression().intensity(), nullable(request.proposedExpression().facialExpression()),
                nullable(request.proposedExpression().gazeTarget()), nullable(request.proposedExpression().gesture()));
        String content = chatModel.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))))
                .getResult().getOutput().getText();
        return parse(content, request.proposedExpression());
    }

    private CharacterCognitionResult parse(String content, ExpressionPlan proposed) {
        try {
            JsonNode root = objectMapper.readTree(jsonObject(content));
            boolean speak = root.path("speak").asBoolean(false);
            String utterance = text(root, "utterance");
            String note = text(root, "memoryNote");
            double importance = unit(root.path("memoryImportance").asDouble(0));
            var expression = new ExpressionPlan(
                    proposed.communicativeIntent(), value(root, "voiceStyle", proposed.voiceStyle()),
                    unit(root.path("intensity").asDouble(proposed.intensity())),
                    value(root, "facialExpression", proposed.facialExpression()),
                    value(root, "gazeTarget", proposed.gazeTarget()),
                    value(root, "gesture", proposed.gesture()),
                    root.path("resumePreviousActivity").asBoolean(proposed.resumePreviousActivity()));
            return new CharacterCognitionResult(speak, utterance, note, importance, expression);
        } catch (Exception failure) {
            throw new IllegalArgumentException("character cognition JSON is invalid", failure);
        }
    }

    private String loadPersona(String location) {
        try (var input = resources.getResource("classpath:" + location).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("persona prompt could not be loaded: " + location, failure);
        }
    }

    private static String memories(List<CharacterMemory> memories) {
        if (memories.isEmpty()) return "(없음)";
        return memories.stream().map(memory -> "- [%s] %s".formatted(memory.kind(), memory.content()))
                .reduce((left, right) -> left + "\n" + right).orElse("(없음)");
    }

    private static String jsonObject(String content) {
        if (content == null) throw new IllegalArgumentException("empty cognition response");
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("JSON object not found");
        return content.substring(start, end + 1);
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() || node.asText().isBlank() ? null : node.asText().trim();
    }

    private static String value(JsonNode root, String field, String fallback) {
        String value = text(root, field);
        return value == null ? fallback : value;
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? "(없음)" : value;
    }

    private static double unit(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }
}
