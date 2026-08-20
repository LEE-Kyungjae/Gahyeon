package com.gahyeonbot.application.life;

import com.gahyeonbot.core.conversation.*;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.life.*;
import com.gahyeonbot.core.session.*;
import com.gahyeonbot.core.world.WorldId;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CharacterConversationMemoryListenerTest {
    @Test
    void recordsACompletedConversationOnlyInTheSelectedCharactersNamespace() {
        var memories = new InMemoryStore();
        CharacterLifeService life = mock(CharacterLifeService.class);
        var listener = new CharacterConversationMemoryListener(
                new CharacterDefinitionRegistry(CharacterCatalogProperties.standard()), memories, life,
                Clock.fixed(Instant.parse("2026-08-19T12:00:00Z"), ZoneOffset.UTC));
        var request = new ConversationRequest("request-1", new ConversationSession(
                new ConversationSessionId("desktop:room"), new ActorId(42), ClientSource.DESKTOP,
                ConversationModality.TEXT, Map.of("character.id", "diana", "world.id", "gahyeon-home")),
                "tester", "오늘 뭐 했어?");

        listener.handle(new CharacterConversationCompleted(request,
                new ConversationResponse("run-1", "책을 읽고 있었어.", List.of(), Duration.ZERO)));

        assertThat(memories.recent(new CharacterId("diana"), new WorldId("gahyeon-home"), 10))
                .extracting(CharacterMemory::kind, CharacterMemory::content)
                .containsExactly(
                        tuple(CharacterMemoryKind.WORKING, "user: 오늘 뭐 했어?"),
                        tuple(CharacterMemoryKind.WORKING, "assistant: 책을 읽고 있었어."));
        assertThat(memories.recent(new CharacterId("gahyeon"), new WorldId("gahyeon-home"), 10)).isEmpty();
        verify(life).observe(eq(new CharacterId("diana")), eq(new WorldId("gahyeon-home")),
                argThat(stimulus -> stimulus.type().equals("user.interaction")));
    }

    @Test
    void turnsTheNextConversationIntoAUserReturnedTriggerWhenAnIntentionIsPending() {
        var memories = new InMemoryStore();
        CharacterLifeService life = mock(CharacterLifeService.class);
        Instant now = Instant.parse("2026-08-19T12:00:00Z");
        var pending = new CharacterLifeState(new CharacterId("diana"), new WorldId("gahyeon-home"), 4,
                "reading", 0.1, 0.2, 0.5, 0.3, 0.1, null, "read", "결과를 물어본다",
                now.minusSeconds(3600), null, now.minusSeconds(60));
        when(life.current(new CharacterId("diana"), new WorldId("gahyeon-home"))).thenReturn(pending);
        var listener = new CharacterConversationMemoryListener(
                new CharacterDefinitionRegistry(CharacterCatalogProperties.standard()), memories, life,
                Clock.fixed(now, ZoneOffset.UTC));
        var request = new ConversationRequest("request-return", new ConversationSession(
                new ConversationSessionId("desktop:return"), new ActorId(42), ClientSource.DESKTOP,
                ConversationModality.TEXT, Map.of("character.id", "diana", "world.id", "gahyeon-home")),
                "tester", "나 왔어");

        listener.handle(new CharacterConversationCompleted(request,
                new ConversationResponse("run-return", "어서 와.", List.of(), Duration.ZERO)));

        verify(life).observe(eq(new CharacterId("diana")), eq(new WorldId("gahyeon-home")),
                argThat(stimulus -> stimulus.type().equals("user.returned")));
    }

    private static final class InMemoryStore implements CharacterMemoryStore {
        private final List<CharacterMemory> values = new ArrayList<>();
        public CharacterMemory append(CharacterMemory memory) { values.add(memory); return memory; }
        public List<CharacterMemory> recent(CharacterId characterId, WorldId worldId, int limit) {
            return values.stream().filter(value -> value.characterId().equals(characterId)
                    && value.worldId().equals(worldId)).limit(limit).toList();
        }
    }
}
