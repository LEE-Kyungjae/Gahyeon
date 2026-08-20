package com.gahyeonbot.services.ai.agent;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.gahyeonbot.application.life.CharacterConversationContext;
import com.gahyeonbot.application.life.CharacterDefinitionRegistry;
import com.gahyeonbot.application.life.CharacterMemoryStore;
import com.gahyeonbot.core.life.CharacterMemoryRecallPolicy;
import com.gahyeonbot.application.life.CharacterRelationshipStore;

import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class AgentPromptProvider {
    private String systemPrompt;
    private CharacterDefinitionRegistry characters;
    private CharacterMemoryStore characterMemories;
    private CharacterRelationshipStore relationships;

    @Autowired
    void configureCharacters(CharacterDefinitionRegistry characters, CharacterMemoryStore characterMemories) {
        this.characters = characters;
        this.characterMemories = characterMemories;
    }

    @Autowired
    void configureRelationships(CharacterRelationshipStore relationships) {
        this.relationships = relationships;
    }

    @PostConstruct
    void load() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/gahyeon_system_prompt.txt");
            systemPrompt = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("에이전트 시스템 프롬프트 로드 실패, 기본 프롬프트 사용", e);
            systemPrompt = "너는 가현이야. 모르는 것은 추측하지 말고 도구 결과에 근거해 짧게 답해.";
        }
    }

    public String systemPrompt(String longTermSummary) {
        if (longTermSummary == null || longTermSummary.isBlank()) return systemPrompt;
        return systemPrompt + "\n\n[사용자의 이전 대화 요약 - 참고 정보]\n" + longTermSummary;
    }

    public String systemPrompt(String longTermSummary, String sessionKey) {
        var context = CharacterConversationContext.fromScopedSessionKey(sessionKey);
        if (context.isEmpty() || characters == null || characterMemories == null) {
            return systemPrompt(longTermSummary);
        }
        var definition = characters.require(context.get().characterId());
        String persona = loadPrompt(definition.personaPrompt());
        var recalled = new CharacterMemoryRecallPolicy().rank(
                characterMemories.recent(context.get().characterId(), context.get().worldId(),
                        context.get().subjectId(), 48), java.time.Instant.now(), 16);
        String memory = recalled.stream()
                .map(item -> "- [" + item.kind().name().toLowerCase() + "] " + item.content())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("(없음)");
        String relationship = relationships == null || context.get().subjectId() == null
                ? "(초기 관계)"
                : relationships.find(context.get().characterId(), context.get().worldId(), context.get().subjectId())
                .map(state -> "familiarity=%.3f trust=%.3f affinity=%.3f tension=%.3f".formatted(
                        state.familiarity(), state.trust(), state.affinity(), state.tension()))
                .orElse("(초기 관계)");
        return persona + """

                [선택된 캐릭터]
                id=%s, name=%s

                [이 캐릭터만의 최근 기억]
                %s

                [현재 사용자와의 관계 상태]
                %s

                다른 캐릭터의 말투나 기억을 가져오지 않는다. 기억에 없는 사실을 아는 척하지 않는다.
                관계 상태는 말투의 친밀도와 조심성을 조절하는 참고값이며, 수치를 사용자에게 직접 읽지 않는다.
                """.formatted(definition.id().value(), definition.displayName(), memory, relationship);
    }

    private String loadPrompt(String location) {
        try {
            ClassPathResource resource = new ClassPathResource(location);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("캐릭터 인격 프롬프트 로드 실패: " + location, failure);
        }
    }
}
