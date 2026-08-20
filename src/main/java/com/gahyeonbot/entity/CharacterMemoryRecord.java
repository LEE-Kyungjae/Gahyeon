package com.gahyeonbot.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "character_memories")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class CharacterMemoryRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "character_id", nullable = false, length = 64) private String characterId;
    @Column(name = "world_id", nullable = false, length = 100) private String worldId;
    @Column(name = "subject_id", length = 160) private String subjectId;
    @Column(nullable = false, length = 32) private String kind;
    @Column(name = "memory_key", length = 160) private String memoryKey;
    @Column(nullable = false, length = 2000) private String content;
    @Column(nullable = false) private double importance;
    @Column(nullable = false) private double confidence;
    @Column(name = "emotional_weight", nullable = false) private double emotionalWeight;
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(name = "last_accessed_at", nullable = false) private Instant lastAccessedAt;
    @Column(nullable = false, unique = true, length = 64) private String fingerprint;
    @Column(name = "superseded_at") private Instant supersededAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
}
