package com.gahyeonbot.repository;

import com.gahyeonbot.entity.CharacterLifeStateRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterLifeStateRecordRepository extends JpaRepository<CharacterLifeStateRecord, CharacterLifeStateRecord.Key> {}
