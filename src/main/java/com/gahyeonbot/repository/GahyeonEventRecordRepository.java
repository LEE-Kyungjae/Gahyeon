package com.gahyeonbot.repository;

import com.gahyeonbot.entity.GahyeonEventRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GahyeonEventRecordRepository extends JpaRepository<GahyeonEventRecord, Long> {
    List<GahyeonEventRecord> findByIdGreaterThanOrderByIdAsc(long id, Pageable pageable);
}
