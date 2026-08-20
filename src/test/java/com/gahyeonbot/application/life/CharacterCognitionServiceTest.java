package com.gahyeonbot.application.life;

import com.gahyeonbot.application.event.GahyeonEventPublisher;
import com.gahyeonbot.core.event.GahyeonEvent;
import com.gahyeonbot.core.event.GahyeonEventDraft;
import com.gahyeonbot.core.life.*;
import com.gahyeonbot.core.world.WorldId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CharacterCognitionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    @Test
    void keepsMemoriesAndCognitionContextIsolatedPerCharacter() {
        var store = new InMemoryMemoryStore();
        store.append(memory("gahyeon", "가현만 아는 약속"));
        store.append(memory("diana", "다이애나만 아는 관찰"));
        var port = new CapturingPort();
        var drafts = new ArrayList<GahyeonEventDraft>();
        CharacterCognitionService service = service(store, port, drafts);

        assertThat(service.process(decision("gahyeon"))).isTrue();
        assertThat(service.process(decision("diana"))).isTrue();

        assertThat(port.requests.get(0).recentMemories()).extracting(CharacterMemory::content)
                .containsExactly("가현만 아는 약속");
        assertThat(port.requests.get(1).recentMemories()).extracting(CharacterMemory::content)
                .containsExactly("다이애나만 아는 관찰");
        assertThat(drafts).extracting(GahyeonEventDraft::type)
                .containsExactly("character.cognition.completed", "character.cognition.completed");
    }

    @Test
    void preservesIntentionalSilenceWithoutInventingAnUtterance() {
        var store = new InMemoryMemoryStore();
        CharacterCognitionPort port = fixed(new CharacterCognitionResult(false, null, "지금은 지켜보기로 함",
                0.3, expression()));
        var drafts = new ArrayList<GahyeonEventDraft>();

        assertThat(service(store, port, drafts).process(decision("gahyeon"))).isTrue();

        assertThat(store.recent(new CharacterId("gahyeon"), new WorldId("home"), 10))
                .extracting(CharacterMemory::kind).containsExactly(CharacterMemoryKind.REFLECTION);
        assertThat(drafts.getFirst().payload()).containsEntry("spoken", false).doesNotContainKey("utterance");
    }

    @Test
    void recordsFailureWithoutPoisoningTheNextCognitionRun() {
        var store = new InMemoryMemoryStore();
        var port = new CharacterCognitionPort() {
            int attempts;
            public boolean isReady() { return true; }
            public CharacterCognitionResult generate(CharacterCognitionRequest request) {
                if (attempts++ == 0) throw new IllegalStateException("provider down");
                return new CharacterCognitionResult(true, "다시 생각해 봤어.", null, 0.4, expression());
            }
        };
        var drafts = new ArrayList<GahyeonEventDraft>();
        CharacterCognitionService service = service(store, port, drafts);

        assertThat(service.process(decision("gahyeon"))).isFalse();
        assertThat(service.process(decision("gahyeon"))).isTrue();

        assertThat(drafts).extracting(GahyeonEventDraft::type)
                .containsExactly("character.cognition.failed", "character.cognition.completed");
        assertThat(drafts.get(1).payload()).containsEntry("utterance", "다시 생각해 봤어.")
                .containsKeys("expressionPlan", "voiceProfile", "expressionProfile");
    }

    @Test
    void presentsOnlySpokenCognitionAndRendererFailureDoesNotPoisonLifeState() {
        var store = new InMemoryMemoryStore();
        var result = new CharacterCognitionResult(true, "다녀왔어?", null, 0.4, expression());
        var drafts = new ArrayList<GahyeonEventDraft>();
        var presented = new ArrayList<String>();
        @SuppressWarnings("unchecked") ObjectProvider<CharacterCognitionPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(fixed(result));
        CharacterCognitionPresentationPort failing = (character, decision, cognition) -> {
            presented.add(character.id().value());
            throw new IllegalStateException("renderer unavailable");
        };
        var service = new CharacterCognitionService(
                new CharacterDefinitionRegistry(CharacterCatalogProperties.standard()), store, provider,
                draft -> { drafts.add(draft); return mock(GahyeonEvent.class); },
                List.of(failing), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.process(decision("gahyeon"))).isTrue();
        assertThat(presented).containsExactly("gahyeon");
        assertThat(drafts).extracting(GahyeonEventDraft::type)
                .containsExactly("character.cognition.completed");
    }

    private static CharacterCognitionService service(InMemoryMemoryStore store, CharacterCognitionPort port,
            List<GahyeonEventDraft> drafts) {
        @SuppressWarnings("unchecked") ObjectProvider<CharacterCognitionPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        GahyeonEventPublisher publisher = draft -> {
            drafts.add(draft);
            return mock(GahyeonEvent.class);
        };
        return new CharacterCognitionService(new CharacterDefinitionRegistry(CharacterCatalogProperties.standard()),
                store, provider, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CharacterCognitionPort fixed(CharacterCognitionResult result) {
        return new CharacterCognitionPort() {
            public boolean isReady() { return true; }
            public CharacterCognitionResult generate(CharacterCognitionRequest request) { return result; }
        };
    }

    private static LifeDecision decision(String character) {
        CharacterLifeState state = CharacterLifeState.initial(new CharacterId(character), new WorldId("home"), NOW);
        return new LifeDecision(LifeDisposition.COGNITION, "test_reason", state, expression());
    }

    private static ExpressionPlan expression() {
        return new ExpressionPlan("contextual_reaction", "natural", 0.5, "attentive", "user", "orient", true);
    }

    private static CharacterMemory memory(String character, String content) {
        return new CharacterMemory(0, new CharacterId(character), new WorldId("home"), "episodic", content, 0.7, NOW);
    }

    private static final class CapturingPort implements CharacterCognitionPort {
        final List<CharacterCognitionRequest> requests = new ArrayList<>();
        public boolean isReady() { return true; }
        public CharacterCognitionResult generate(CharacterCognitionRequest request) {
            requests.add(request);
            return new CharacterCognitionResult(false, null, null, 0, request.proposedExpression());
        }
    }

    private static final class InMemoryMemoryStore implements CharacterMemoryStore {
        final List<CharacterMemory> values = new ArrayList<>();
        public CharacterMemory append(CharacterMemory memory) { values.add(memory); return memory; }
        public List<CharacterMemory> recent(CharacterId characterId, WorldId worldId, int limit) {
            return values.stream().filter(value -> value.characterId().equals(characterId) && value.worldId().equals(worldId))
                    .sorted(Comparator.comparing(CharacterMemory::createdAt).reversed()).limit(limit).toList();
        }
    }
}
