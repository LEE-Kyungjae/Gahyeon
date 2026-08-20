package com.gahyeonbot.application.life;

import com.gahyeonbot.core.life.*;
import com.gahyeonbot.core.world.WorldId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

@Service
public class CharacterRelationshipService {
    private final CharacterDefinitionRegistry characters;
    private final CharacterRelationshipStore relationships;
    private final Clock clock;

    @Autowired
    public CharacterRelationshipService(CharacterDefinitionRegistry characters, CharacterRelationshipStore relationships) {
        this(characters, relationships, Clock.systemUTC());
    }

    CharacterRelationshipService(CharacterDefinitionRegistry characters, CharacterRelationshipStore relationships, Clock clock) {
        this.characters = characters;
        this.relationships = relationships;
        this.clock = clock;
    }

    @Transactional
    public CharacterRelationshipState current(CharacterId characterId, WorldId worldId, String subjectId) {
        characters.require(characterId);
        return relationships.find(characterId, worldId, subjectId)
                .orElseGet(() -> relationships.save(CharacterRelationshipState.initial(
                        characterId, worldId, subjectId, clock.instant())));
    }

    @Transactional
    public CharacterRelationshipState apply(CharacterId characterId, WorldId worldId, String subjectId,
            CharacterMemoryCandidate evidence) {
        if (evidence.kind() != CharacterMemoryKind.RELATIONSHIP) throw new IllegalArgumentException("relationship evidence is required");
        CharacterRelationshipState before = current(characterId, worldId, subjectId);
        double emotional = evidence.emotionalWeight();
        double familiarity = unit(before.familiarity() + 0.03 * evidence.importance());
        double trust = unit(before.trust() + 0.06 * emotional * evidence.confidence());
        double affinity = unit(before.affinity() + 0.05 * emotional * evidence.importance());
        double tension = unit(before.tension() + (emotional < 0 ? -emotional * 0.07 : -emotional * 0.025));
        var next = new CharacterRelationshipState(characterId, worldId, subjectId, before.revision() + 1,
                familiarity, trust, affinity, tension, clock.instant(), clock.instant());
        return relationships.save(next);
    }

    private static double unit(double value) { return Math.max(0, Math.min(1, value)); }
}
