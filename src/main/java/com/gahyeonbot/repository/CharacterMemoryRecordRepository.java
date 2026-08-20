package com.gahyeonbot.repository;

import com.gahyeonbot.entity.CharacterMemoryRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface CharacterMemoryRecordRepository extends JpaRepository<CharacterMemoryRecord, Long> {
    boolean existsByFingerprint(String fingerprint);

    java.util.Optional<CharacterMemoryRecord> findFirstByCharacterIdAndWorldIdAndSubjectIdAndKindAndMemoryKeyAndSupersededAtIsNull(
            String characterId, String worldId, String subjectId, String kind, String memoryKey);

    @Query("""
            select memory from CharacterMemoryRecord memory
            where memory.characterId = :characterId and memory.worldId = :worldId
              and memory.supersededAt is null
              and ((:subjectId is null and memory.subjectId is null)
                   or (:subjectId is not null and (memory.subjectId = :subjectId or memory.subjectId is null)))
            order by memory.createdAt desc
            """)
    List<CharacterMemoryRecord> findVisible(
            @Param("characterId") String characterId,
            @Param("worldId") String worldId,
            @Param("subjectId") String subjectId,
            Pageable pageable);
}
