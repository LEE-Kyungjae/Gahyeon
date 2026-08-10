package com.gahyeonbot.adapters.world;

import com.gahyeonbot.application.world.WorldStateStore;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.core.world.WorldPosition;
import com.gahyeonbot.core.world.WorldStateSnapshot;
import com.gahyeonbot.entity.GahyeonWorldStateRecord;
import com.gahyeonbot.repository.GahyeonWorldStateRecordRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JpaWorldStateStore implements WorldStateStore {
    private final GahyeonWorldStateRecordRepository repository;

    public JpaWorldStateStore(GahyeonWorldStateRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<WorldStateSnapshot> find(WorldId worldId) {
        return repository.findById(worldId.value()).map(this::toSnapshot);
    }

    @Override
    public WorldStateSnapshot save(WorldStateSnapshot snapshot) {
        GahyeonWorldStateRecord record = repository.findById(snapshot.worldId().value())
                .orElseGet(() -> GahyeonWorldStateRecord.builder()
                        .worldId(snapshot.worldId().value())
                        .build());
        record.setRevision(snapshot.revision());
        record.setCurrentRoom(snapshot.currentRoom());
        record.setPositionX(snapshot.position().x());
        record.setPositionY(snapshot.position().y());
        record.setPositionZ(snapshot.position().z());
        record.setActivity(snapshot.activity());
        record.setActivityStartedAt(snapshot.activityStartedAt());
        record.setOutfit(snapshot.outfit());
        record.setWorldTime(snapshot.worldTime());
        record.setEmotion(snapshot.emotion());
        record.setEmotionIntensity(snapshot.emotionIntensity());
        record.setInteractionTarget(snapshot.interactionTarget());
        record.setUpdatedAt(snapshot.updatedAt());
        return toSnapshot(repository.saveAndFlush(record));
    }

    private WorldStateSnapshot toSnapshot(GahyeonWorldStateRecord record) {
        return new WorldStateSnapshot(
                new WorldId(record.getWorldId()),
                record.getRevision(),
                record.getCurrentRoom(),
                new WorldPosition(record.getPositionX(), record.getPositionY(), record.getPositionZ()),
                record.getActivity(),
                record.getActivityStartedAt(),
                record.getOutfit(),
                record.getWorldTime(),
                record.getEmotion(),
                record.getEmotionIntensity(),
                record.getInteractionTarget(),
                record.getUpdatedAt());
    }
}
