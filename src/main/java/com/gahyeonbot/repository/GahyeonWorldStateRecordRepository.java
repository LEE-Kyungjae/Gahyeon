package com.gahyeonbot.repository;

import com.gahyeonbot.entity.GahyeonWorldStateRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GahyeonWorldStateRecordRepository
        extends JpaRepository<GahyeonWorldStateRecord, String> {
}
