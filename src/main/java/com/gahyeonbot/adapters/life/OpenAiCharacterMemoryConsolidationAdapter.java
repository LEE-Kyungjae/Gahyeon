package com.gahyeonbot.adapters.life;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gahyeonbot.application.life.*;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
public final class OpenAiCharacterMemoryConsolidationAdapter implements CharacterMemoryConsolidationPort {
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public OpenAiCharacterMemoryConsolidationAdapter(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public List<CharacterMemoryCandidate> consolidate(CharacterMemoryConsolidationRequest request) {
        String system = """
                너는 장기 기억 통합기다. 숨겨진 추론은 출력하지 말고 JSON 배열 하나만 출력한다.
                대화에 명시된 사실만 저장한다. 추측, 일반 상식, 인사, 일회성 잡담은 저장하지 않는다.
                semantic은 사용자의 안정적인 선호나 사실, relationship은 이 캐릭터와 사용자의 관계 변화,
                prospective는 사용자가 명시적으로 미래에 하기로 한 일 또는 캐릭터가 기억해 달라고 받은 약속이다.
                같은 내용이 기존 기억에 있으면 출력하지 않는다. 최대 8개다.
                memoryKey는 주제를 나타내는 안정적인 영문 slug다. 값이 바뀌어도 같은 주제면 같은 키를 쓴다.
                예: preference.movie.horror, profile.job, promise.result.
                형식: [{"kind":"semantic","memoryKey":"preference.movie.horror","content":"짧은 3인칭 기억","importance":0.7,
                "confidence":0.9,"emotionalWeight":0.0,"expiresAt":null}]
                kind은 episodic, semantic, relationship, prospective 중 하나다.
                """;
        String existing = request.existingMemories().stream()
                .filter(memory -> memory.kind() != com.gahyeonbot.core.life.CharacterMemoryKind.WORKING)
                .map(memory -> "- [" + memory.kind().name().toLowerCase() + "] " + memory.content())
                .reduce((left, right) -> left + "\n" + right).orElse("(없음)");
        String user = """
                [캐릭터] %s
                [사용자 발화] %s
                [캐릭터 응답] %s
                [기존 기억]
                %s
                """.formatted(request.character().displayName(), request.userMessage(),
                request.assistantMessage(), existing);
        String content = chatModel.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))))
                .getResult().getOutput().getText();
        return parse(content);
    }

    private List<CharacterMemoryCandidate> parse(String content) {
        try {
            JsonNode root = objectMapper.readTree(jsonArray(content));
            if (!root.isArray() || root.size() > 8) throw new IllegalArgumentException("invalid candidate array");
            var result = new ArrayList<CharacterMemoryCandidate>();
            for (JsonNode node : root) {
                var kind = com.gahyeonbot.core.life.CharacterMemoryKind.parse(node.path("kind").asText());
                String memoryKey = nullableText(node, "memoryKey");
                String value = node.path("content").asText();
                JsonNode expiry = node.get("expiresAt");
                Instant expiresAt = expiry == null || expiry.isNull() || expiry.asText().isBlank()
                        ? null : Instant.parse(expiry.asText());
                result.add(new CharacterMemoryCandidate(kind, memoryKey, value,
                        unit(node.path("importance").asDouble(0.5)),
                        unit(node.path("confidence").asDouble(0)),
                        signedUnit(node.path("emotionalWeight").asDouble(0)), expiresAt));
            }
            return List.copyOf(result);
        } catch (Exception failure) {
            throw new IllegalArgumentException("memory consolidation JSON is invalid", failure);
        }
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }

    private static String jsonArray(String content) {
        if (content == null) throw new IllegalArgumentException("empty memory response");
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end < start) throw new IllegalArgumentException("JSON array not found");
        return content.substring(start, end + 1);
    }

    private static double unit(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }

    private static double signedUnit(double value) {
        return Double.isFinite(value) ? Math.max(-1, Math.min(1, value)) : 0;
    }
}
