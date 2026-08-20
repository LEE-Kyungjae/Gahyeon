package com.gahyeonbot.application.life;

import com.gahyeonbot.application.event.GahyeonEventPublisher;
import com.gahyeonbot.core.event.GahyeonEventDraft;
import com.gahyeonbot.core.life.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;

@Service
public final class CharacterMemoryConsolidationService {
    private final CharacterDefinitionRegistry characters;
    private final CharacterMemoryStore memories;
    private final ObjectProvider<CharacterMemoryConsolidationPort> portProvider;
    private final GahyeonEventPublisher events;
    private final CharacterLifeService life;
    private final CharacterRelationshipService relationships;
    private final Clock clock;

    @Autowired
    public CharacterMemoryConsolidationService(CharacterDefinitionRegistry characters, CharacterMemoryStore memories,
            ObjectProvider<CharacterMemoryConsolidationPort> portProvider, GahyeonEventPublisher events,
            CharacterLifeService life, CharacterRelationshipService relationships) {
        this(characters, memories, portProvider, events, life, relationships, Clock.systemUTC());
    }

    CharacterMemoryConsolidationService(CharacterDefinitionRegistry characters, CharacterMemoryStore memories,
            ObjectProvider<CharacterMemoryConsolidationPort> portProvider, GahyeonEventPublisher events,
            CharacterLifeService life, CharacterRelationshipService relationships, Clock clock) {
        this.characters = characters;
        this.memories = memories;
        this.portProvider = portProvider;
        this.events = events;
        this.life = life;
        this.relationships = relationships;
        this.clock = clock;
    }

    public int consolidate(CharacterConversationCompleted event) {
        var context = CharacterConversationContext.from(event.request().session()).orElse(null);
        if (context == null) return 0;
        var now = clock.instant();
        var character = characters.require(context.characterId());
        int persisted = memories.appendIfAbsent(new CharacterMemory(0, context.characterId(), context.worldId(),
                context.subjectId(), CharacterMemoryKind.EPISODIC,
                "사용자가 '" + limited(event.request().message()) + "'라고 말했고 "
                        + character.displayName() + "이(가) '" + limited(event.response().content()) + "'라고 답했다.",
                0.55, 1, 0, null, now, now)) ? 1 : 0;
        CharacterMemoryConsolidationPort port = portProvider.getIfAvailable();
        if (port == null || !port.isReady()) {
            publish("character.memory.consolidation.skipped", context, persisted, "runtime_not_ready");
            return persisted;
        }
        try {
            var request = new CharacterMemoryConsolidationRequest(character, context.worldId(), context.subjectId(),
                    event.request().message(), event.response().content(),
                    memories.recent(context.characterId(), context.worldId(), context.subjectId(), 24));
            List<CharacterMemoryCandidate> candidates = port.consolidate(request);
            if (candidates.size() > 8) throw new IllegalArgumentException("too many memory candidates");
            for (CharacterMemoryCandidate candidate : candidates) {
                if (candidate.confidence() < 0.55) continue;
                CharacterMemoryMergeResult mergeResult = memories.merge(new CharacterMemory(0, context.characterId(), context.worldId(),
                        context.subjectId(), candidate.kind(), candidate.memoryKey(), candidate.content(), candidate.importance(),
                        candidate.confidence(), candidate.emotionalWeight(), candidate.expiresAt(), now, now));
                if (mergeResult == CharacterMemoryMergeResult.INSERTED
                        || mergeResult == CharacterMemoryMergeResult.SUPERSEDED) {
                    persisted++;
                    if (candidate.kind() == CharacterMemoryKind.PROSPECTIVE) {
                        life.observe(context.characterId(), context.worldId(), new LifeStimulus(
                                "prospective.intention", candidate.importance(), candidate.content(), false, now));
                    } else if (candidate.kind() == CharacterMemoryKind.RELATIONSHIP) {
                        relationships.apply(context.characterId(), context.worldId(), context.subjectId(), candidate);
                    }
                }
            }
            publish("character.memory.consolidation.completed", context, persisted, null);
            return persisted;
        } catch (RuntimeException failure) {
            publish("character.memory.consolidation.failed", context, persisted, failure.getClass().getSimpleName());
            return persisted;
        }
    }

    private void publish(String type, CharacterConversationContext context, int persisted, String cause) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("characterId", context.characterId().value());
        payload.put("worldId", context.worldId().value());
        payload.put("persisted", persisted);
        if (cause != null) payload.put("cause", cause);
        events.publish(GahyeonEventDraft.world(type, context.worldId().value(),
                "memory:" + context.characterId().value() + ":" + UUID.randomUUID(), Map.copyOf(payload)));
    }

    private static String limited(String value) {
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 400) + "…";
    }
}
