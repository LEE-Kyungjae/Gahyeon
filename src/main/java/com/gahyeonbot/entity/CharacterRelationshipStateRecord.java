package com.gahyeonbot.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "character_relationship_states")
@IdClass(CharacterRelationshipStateRecord.Key.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class CharacterRelationshipStateRecord {
    @Id @Column(name = "character_id", length = 64) private String characterId;
    @Id @Column(name = "world_id", length = 100) private String worldId;
    @Id @Column(name = "subject_id", length = 160) private String subjectId;
    @Version @Column(name = "storage_version", nullable = false) private long storageVersion;
    @Column(nullable = false) private long revision;
    @Column(nullable = false) private double familiarity;
    @Column(nullable = false) private double trust;
    @Column(nullable = false) private double affinity;
    @Column(nullable = false) private double tension;
    @Column(name = "last_interaction_at") private Instant lastInteractionAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Key implements java.io.Serializable {
        private String characterId;
        private String worldId;
        private String subjectId;
    }
}
