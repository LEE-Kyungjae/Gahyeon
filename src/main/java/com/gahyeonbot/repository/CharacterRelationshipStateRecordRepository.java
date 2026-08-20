package com.gahyeonbot.repository;

import com.gahyeonbot.entity.CharacterRelationshipStateRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterRelationshipStateRecordRepository extends JpaRepository<CharacterRelationshipStateRecord, CharacterRelationshipStateRecord.Key> {}
