package com.gahyeonbot.entity;

import com.gahyeonbot.core.world.WorldActivity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "gahyeon_world_states")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GahyeonWorldStateRecord {
    @Id
    @Column(name = "world_id", length = 100)
    private String worldId;

    @Version
    @Column(name = "storage_version", nullable = false)
    private long storageVersion;

    @Column(nullable = false)
    private long revision;

    @Column(name = "current_room", nullable = false, length = 100)
    private String currentRoom;

    @Column(name = "position_x", nullable = false)
    private double positionX;

    @Column(name = "position_y", nullable = false)
    private double positionY;

    @Column(name = "position_z", nullable = false)
    private double positionZ;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private WorldActivity activity;

    @Column(name = "activity_started_at", nullable = false)
    private Instant activityStartedAt;

    @Column(nullable = false, length = 100)
    private String outfit;

    @Column(name = "world_time", nullable = false)
    private Instant worldTime;

    @Column(nullable = false, length = 80)
    private String emotion;

    @Column(name = "emotion_intensity", nullable = false)
    private double emotionIntensity;

    @Column(name = "interaction_target", length = 120)
    private String interactionTarget;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
