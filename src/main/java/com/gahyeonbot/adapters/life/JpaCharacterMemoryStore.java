package com.gahyeonbot.adapters.life;

import com.gahyeonbot.application.life.CharacterMemoryStore;
import com.gahyeonbot.core.life.CharacterId;
import com.gahyeonbot.core.life.CharacterMemory;
import com.gahyeonbot.core.life.CharacterMemoryKind;
import com.gahyeonbot.core.life.CharacterMemoryMergeResult;
import com.gahyeonbot.core.world.WorldId;
import com.gahyeonbot.entity.CharacterMemoryRecord;
import com.gahyeonbot.repository.CharacterMemoryRecordRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class JpaCharacterMemoryStore implements CharacterMemoryStore {
    private final CharacterMemoryRecordRepository repository;

    public JpaCharacterMemoryStore(CharacterMemoryRecordRepository repository) {
        this.repository = repository;
    }

    @Override
    public CharacterMemory append(CharacterMemory memory) {
        return save(memory, fingerprint(memory, memory.createdAt().toString()));
    }

    @Override
    public boolean appendIfAbsent(CharacterMemory memory) {
        String fingerprint = fingerprint(memory, "stable");
        if (repository.existsByFingerprint(fingerprint)) return false;
        save(memory, fingerprint);
        return true;
    }

    private CharacterMemory save(CharacterMemory memory, String fingerprint) {
        CharacterMemoryRecord saved = repository.save(CharacterMemoryRecord.builder()
                .characterId(memory.characterId().value()).worldId(memory.worldId().value())
                .subjectId(memory.subjectId()).kind(memory.kind().name().toLowerCase())
                .memoryKey(memory.memoryKey())
                .content(memory.content()).importance(memory.importance())
                .confidence(memory.confidence()).emotionalWeight(memory.emotionalWeight())
                .expiresAt(memory.expiresAt()).lastAccessedAt(memory.lastAccessedAt())
                .fingerprint(fingerprint)
                .createdAt(memory.createdAt()).build());
        return snapshot(saved);
    }

    @Override
    @Transactional
    public CharacterMemoryMergeResult merge(CharacterMemory memory) {
        if (memory.memoryKey() == null) {
            return appendIfAbsent(memory) ? CharacterMemoryMergeResult.INSERTED : CharacterMemoryMergeResult.DUPLICATE;
        }
        String kind = memory.kind().name().toLowerCase();
        var existing = repository.findFirstByCharacterIdAndWorldIdAndSubjectIdAndKindAndMemoryKeyAndSupersededAtIsNull(
                memory.characterId().value(), memory.worldId().value(), memory.subjectId(), kind, memory.memoryKey());
        if (existing.isEmpty()) {
            appendIfAbsent(memory);
            return CharacterMemoryMergeResult.INSERTED;
        }
        CharacterMemoryRecord current = existing.get();
        String normalizedNew = normalize(memory.content());
        if (normalize(current.getContent()).equals(normalizedNew)) return CharacterMemoryMergeResult.DUPLICATE;
        if (memory.confidence() + 0.05 < current.getConfidence()) {
            return CharacterMemoryMergeResult.REJECTED_LOWER_CONFIDENCE;
        }
        current.setSupersededAt(memory.createdAt());
        repository.saveAndFlush(current);
        appendIfAbsent(memory);
        return CharacterMemoryMergeResult.SUPERSEDED;
    }

    @Override
    public List<CharacterMemory> recent(CharacterId characterId, WorldId worldId, int limit) {
        return recent(characterId, worldId, null, limit);
    }

    @Override
    public List<CharacterMemory> recent(CharacterId characterId, WorldId worldId, String subjectId, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return repository.findVisible(characterId.value(), worldId.value(), subjectId, PageRequest.of(0, limit)).stream()
                .map(this::snapshot).toList();
    }

    private CharacterMemory snapshot(CharacterMemoryRecord record) {
        return new CharacterMemory(record.getId(), new CharacterId(record.getCharacterId()),
                new WorldId(record.getWorldId()), record.getSubjectId(), CharacterMemoryKind.parse(record.getKind()),
                record.getMemoryKey(), record.getContent(), record.getImportance(), record.getConfidence(), record.getEmotionalWeight(),
                record.getExpiresAt(), record.getLastAccessedAt(), record.getCreatedAt());
    }

    private String fingerprint(CharacterMemory memory, String salt) {
        String canonical = String.join("\n", memory.characterId().value(), memory.worldId().value(),
                memory.subjectId() == null ? "global" : memory.subjectId(), memory.kind().name(),
                memory.memoryKey() == null ? "unkeyed" : memory.memoryKey(),
                memory.content().trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT), salt);
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
