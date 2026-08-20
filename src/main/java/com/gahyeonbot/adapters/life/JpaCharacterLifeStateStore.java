package com.gahyeonbot.adapters.life;

import com.gahyeonbot.application.life.CharacterLifeStateStore;
import com.gahyeonbot.core.life.CharacterId;
import com.gahyeonbot.core.life.CharacterLifeState;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.entity.CharacterLifeStateRecord;
import com.gahyeonbot.repository.CharacterLifeStateRecordRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public final class JpaCharacterLifeStateStore implements CharacterLifeStateStore {
    private final CharacterLifeStateRecordRepository repository;

    public JpaCharacterLifeStateStore(CharacterLifeStateRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<CharacterLifeState> find(CharacterId characterId, WorldId worldId) {
        return repository.findById(new CharacterLifeStateRecord.Key(characterId.value(), worldId.value())).map(this::snapshot);
    }

    @Override
    public CharacterLifeState save(CharacterLifeState state) {
        var key = new CharacterLifeStateRecord.Key(state.characterId().value(), state.worldId().value());
        CharacterLifeStateRecord record = repository.findById(key).orElseGet(() -> CharacterLifeStateRecord.builder()
                .characterId(state.characterId().value()).worldId(state.worldId().value()).build());
        record.setRevision(state.revision());
        record.setActivity(state.activity());
        record.setValence(state.valence());
        record.setArousal(state.arousal());
        record.setSocialNeed(state.socialNeed());
        record.setCuriosityNeed(state.curiosityNeed());
        record.setRestNeed(state.restNeed());
        record.setAttentionTarget(state.attentionTarget());
        record.setCurrentGoal(state.currentGoal());
        record.setProspectiveIntention(state.prospectiveIntention());
        record.setLastInteractionAt(state.lastInteractionAt());
        record.setLastInitiativeAt(state.lastInitiativeAt());
        record.setUpdatedAt(state.updatedAt());
        return snapshot(repository.saveAndFlush(record));
    }

    private CharacterLifeState snapshot(CharacterLifeStateRecord record) {
        return new CharacterLifeState(new CharacterId(record.getCharacterId()), new WorldId(record.getWorldId()),
                record.getRevision(), record.getActivity(), record.getValence(), record.getArousal(),
                record.getSocialNeed(), record.getCuriosityNeed(), record.getRestNeed(), record.getAttentionTarget(),
                record.getCurrentGoal(), record.getProspectiveIntention(), record.getLastInteractionAt(),
                record.getLastInitiativeAt(), record.getUpdatedAt());
    }
}
