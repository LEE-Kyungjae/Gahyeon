package com.gahyeonbot.adapters.life;

import com.gahyeonbot.application.life.CharacterRelationshipStore;
import com.gahyeonbot.core.life.*;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.entity.CharacterRelationshipStateRecord;
import com.gahyeonbot.repository.CharacterRelationshipStateRecordRepository;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public final class JpaCharacterRelationshipStore implements CharacterRelationshipStore {
    private final CharacterRelationshipStateRecordRepository repository;
    public JpaCharacterRelationshipStore(CharacterRelationshipStateRecordRepository repository) { this.repository = repository; }

    public Optional<CharacterRelationshipState> find(CharacterId characterId, WorldId worldId, String subjectId) {
        return repository.findById(new CharacterRelationshipStateRecord.Key(
                characterId.value(), worldId.value(), subjectId)).map(this::snapshot);
    }

    public CharacterRelationshipState save(CharacterRelationshipState state) {
        var key = new CharacterRelationshipStateRecord.Key(
                state.characterId().value(), state.worldId().value(), state.subjectId());
        var record = repository.findById(key).orElseGet(() -> CharacterRelationshipStateRecord.builder()
                .characterId(state.characterId().value()).worldId(state.worldId().value())
                .subjectId(state.subjectId()).build());
        record.setRevision(state.revision()); record.setFamiliarity(state.familiarity());
        record.setTrust(state.trust()); record.setAffinity(state.affinity()); record.setTension(state.tension());
        record.setLastInteractionAt(state.lastInteractionAt()); record.setUpdatedAt(state.updatedAt());
        return snapshot(repository.saveAndFlush(record));
    }

    private CharacterRelationshipState snapshot(CharacterRelationshipStateRecord record) {
        return new CharacterRelationshipState(new CharacterId(record.getCharacterId()), new WorldId(record.getWorldId()),
                record.getSubjectId(), record.getRevision(), record.getFamiliarity(), record.getTrust(),
                record.getAffinity(), record.getTension(), record.getLastInteractionAt(), record.getUpdatedAt());
    }
}
