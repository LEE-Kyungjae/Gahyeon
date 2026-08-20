package com.gahyeonbot.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "character_life_states")
@IdClass(CharacterLifeStateRecord.Key.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CharacterLifeStateRecord {
    @Id @Column(name = "character_id", length = 64) private String characterId;
    @Id @Column(name = "world_id", length = 100) private String worldId;
    @Version @Column(name = "storage_version", nullable = false) private long storageVersion;
    @Column(nullable = false) private long revision;
    @Column(nullable = false, length = 80) private String activity;
    @Column(nullable = false) private double valence;
    @Column(nullable = false) private double arousal;
    @Column(name = "social_need", nullable = false) private double socialNeed;
    @Column(name = "curiosity_need", nullable = false) private double curiosityNeed;
    @Column(name = "rest_need", nullable = false) private double restNeed;
    @Column(name = "attention_target", length = 200) private String attentionTarget;
    @Column(name = "current_goal", length = 200) private String currentGoal;
    @Column(name = "prospective_intention", length = 500) private String prospectiveIntention;
    @Column(name = "last_interaction_at") private Instant lastInteractionAt;
    @Column(name = "last_initiative_at") private Instant lastInitiativeAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements java.io.Serializable {
        private String characterId;
        private String worldId;
    }
}
