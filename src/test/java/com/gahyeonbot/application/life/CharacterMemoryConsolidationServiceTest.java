package com.gahyeonbot.application.life;

import com.gahyeonbot.application.event.GahyeonEventPublisher;
import com.gahyeonbot.core.conversation.*;
import com.gahyeonbot.core.event.*;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.life.*;
import com.gahyeonbot.core.session.*;
import com.gahyeonbot.core.world.WorldId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CharacterMemoryConsolidationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    @Test
    void consolidatesTypedMemoriesOnceAndRejectsLowConfidenceCandidates() {
        var store = new InMemoryStore();
        CharacterMemoryConsolidationPort port = fixed(List.of(
                new CharacterMemoryCandidate(CharacterMemoryKind.SEMANTIC,
                        "사용자는 공포영화를 좋아하지 않는다.", 0.8, 0.95, -0.2, null),
                new CharacterMemoryCandidate(CharacterMemoryKind.RELATIONSHIP,
                        "사용자는 가현에게 편하게 농담한다.", 0.6, 0.4, 0.2, null),
                new CharacterMemoryCandidate(CharacterMemoryKind.PROSPECTIVE,
                        "사용자는 내일 결과를 알려주기로 했다.", 0.7, 0.9, 0.1, null)));
        var drafts = new ArrayList<GahyeonEventDraft>();
        CharacterLifeService life = mock(CharacterLifeService.class);
        CharacterMemoryConsolidationService service = service(store, port, drafts, life);
        CharacterConversationCompleted event = conversation(42);

        assertThat(service.consolidate(event)).isEqualTo(3);
        assertThat(service.consolidate(event)).isZero();

        assertThat(store.values).extracting(CharacterMemory::kind)
                .containsExactly(CharacterMemoryKind.EPISODIC, CharacterMemoryKind.SEMANTIC,
                        CharacterMemoryKind.PROSPECTIVE);
        assertThat(store.values).extracting(CharacterMemory::subjectId).containsOnly("actor:42");
        assertThat(drafts).extracting(GahyeonEventDraft::type)
                .containsOnly("character.memory.consolidation.completed");
        verify(life, times(1)).observe(eq(new CharacterId("gahyeon")), eq(new WorldId("gahyeon-home")),
                argThat(stimulus -> stimulus.type().equals("prospective.intention")
                        && stimulus.subject().contains("내일 결과")));
    }

    @Test
    void providerFailureKeepsTheFactualEpisodeAndAllowsLaterRecovery() {
        var store = new InMemoryStore();
        var port = new CharacterMemoryConsolidationPort() {
            int attempts;
            public boolean isReady() { return true; }
            public List<CharacterMemoryCandidate> consolidate(CharacterMemoryConsolidationRequest request) {
                if (attempts++ == 0) throw new IllegalStateException("provider down");
                return List.of(new CharacterMemoryCandidate(CharacterMemoryKind.SEMANTIC,
                        "사용자는 결과 설명을 선호한다.", 0.7, 0.9, 0, null));
            }
        };
        var drafts = new ArrayList<GahyeonEventDraft>();
        CharacterMemoryConsolidationService service = service(store, port, drafts);

        assertThat(service.consolidate(conversation(42))).isEqualTo(1);
        assertThat(service.consolidate(conversation(42))).isEqualTo(1);

        assertThat(store.values).extracting(CharacterMemory::kind)
                .containsExactly(CharacterMemoryKind.EPISODIC, CharacterMemoryKind.SEMANTIC);
        assertThat(drafts).extracting(GahyeonEventDraft::type).containsExactly(
                "character.memory.consolidation.failed", "character.memory.consolidation.completed");
    }

    @Test
    void identicalFactsRemainSeparateAcrossUsers() {
        var store = new InMemoryStore();
        CharacterMemoryConsolidationService service = service(store, fixed(List.of()), new ArrayList<>());

        assertThat(service.consolidate(conversation(42))).isEqualTo(1);
        assertThat(service.consolidate(conversation(77))).isEqualTo(1);

        assertThat(store.values).extracting(CharacterMemory::subjectId)
                .containsExactly("actor:42", "actor:77");
    }

    @Test
    void appliesAcceptedRelationshipMemoryToThePersistentRelationshipState() {
        var store = new InMemoryStore();
        CharacterRelationshipService relationships = mock(CharacterRelationshipService.class);
        var candidate = new CharacterMemoryCandidate(CharacterMemoryKind.RELATIONSHIP,
                "relationship.comfort", "사용자는 가현에게 편하게 농담한다.", 0.8, 0.9, 0.7, null);
        CharacterMemoryConsolidationService service = service(
                store, fixed(List.of(candidate)), new ArrayList<>(), mock(CharacterLifeService.class), relationships);

        assertThat(service.consolidate(conversation(42))).isEqualTo(2);

        verify(relationships).apply(new CharacterId("gahyeon"), new WorldId("gahyeon-home"), "actor:42", candidate);
    }

    private static CharacterMemoryConsolidationService service(InMemoryStore store,
            CharacterMemoryConsolidationPort port, List<GahyeonEventDraft> drafts) {
        return service(store, port, drafts, mock(CharacterLifeService.class));
    }

    private static CharacterMemoryConsolidationService service(InMemoryStore store,
            CharacterMemoryConsolidationPort port, List<GahyeonEventDraft> drafts, CharacterLifeService life) {
        return service(store, port, drafts, life, mock(CharacterRelationshipService.class));
    }

    private static CharacterMemoryConsolidationService service(InMemoryStore store,
            CharacterMemoryConsolidationPort port, List<GahyeonEventDraft> drafts, CharacterLifeService life,
            CharacterRelationshipService relationships) {
        @SuppressWarnings("unchecked") ObjectProvider<CharacterMemoryConsolidationPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        GahyeonEventPublisher publisher = draft -> {
            drafts.add(draft);
            return mock(GahyeonEvent.class);
        };
        return new CharacterMemoryConsolidationService(
                new CharacterDefinitionRegistry(CharacterCatalogProperties.standard()), store, provider, publisher,
                life, relationships, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CharacterMemoryConsolidationPort fixed(List<CharacterMemoryCandidate> candidates) {
        return new CharacterMemoryConsolidationPort() {
            public boolean isReady() { return true; }
            public List<CharacterMemoryCandidate> consolidate(CharacterMemoryConsolidationRequest request) {
                return candidates;
            }
        };
    }

    private static CharacterConversationCompleted conversation(long actorId) {
        var request = new ConversationRequest("request-" + actorId, new ConversationSession(
                new ConversationSessionId("desktop:room-" + actorId), new ActorId(actorId), ClientSource.DESKTOP,
                ConversationModality.TEXT, Map.of("character.id", "gahyeon", "world.id", "gahyeon-home")),
                "tester", "나는 공포영화를 별로 좋아하지 않아. 내일 결과를 알려줄게.");
        return new CharacterConversationCompleted(request,
                new ConversationResponse("run-" + actorId, "알겠어. 기억해 둘게.", List.of(), Duration.ZERO));
    }

    private static final class InMemoryStore implements CharacterMemoryStore {
        final List<CharacterMemory> values = new ArrayList<>();
        final Set<String> fingerprints = new HashSet<>();
        public CharacterMemory append(CharacterMemory memory) { values.add(memory); return memory; }
        public boolean appendIfAbsent(CharacterMemory memory) {
            String key = memory.characterId() + "|" + memory.worldId() + "|" + memory.subjectId()
                    + "|" + memory.kind() + "|" + memory.content();
            if (!fingerprints.add(key)) return false;
            values.add(memory);
            return true;
        }
        public List<CharacterMemory> recent(CharacterId characterId, WorldId worldId, int limit) {
            return values.stream().filter(value -> value.characterId().equals(characterId)
                    && value.worldId().equals(worldId)).limit(limit).toList();
        }
    }
}
