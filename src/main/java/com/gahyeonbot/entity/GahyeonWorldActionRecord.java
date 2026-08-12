package com.gahyeonbot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Schema mapping for the durable world-action ledger.
 *
 * <p>Production schema changes remain owned by Flyway. This mapping also lets
 * Hibernate create the same table for the local H2 development profile.</p>
 */
@Entity
@Table(
        name = "gahyeon_world_actions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_gahyeon_world_actions_pending_world",
                columnNames = "pending_world_id"))
@Getter
@NoArgsConstructor
public class GahyeonWorldActionRecord {
    @Id
    @Column(name = "action_id", length = 100)
    private String actionId;

    @Column(name = "world_id", nullable = false, length = 100)
    private String worldId;

    @Column(name = "pending_world_id", length = 100)
    private String pendingWorldId;

    @Column(name = "expected_revision", nullable = false)
    private long expectedRevision;

    @Column(name = "source_position_x", nullable = false)
    private double sourcePositionX;

    @Column(name = "source_position_y", nullable = false)
    private double sourcePositionY;

    @Column(name = "source_position_z", nullable = false)
    private double sourcePositionZ;

    @Column(name = "target_room", nullable = false, length = 100)
    private String targetRoom;

    @Column(name = "position_x", nullable = false)
    private double positionX;

    @Column(name = "position_y", nullable = false)
    private double positionY;

    @Column(name = "position_z", nullable = false)
    private double positionZ;

    @Column(nullable = false, length = 40)
    private String activity;

    @Column(name = "interaction_target", length = 120)
    private String interactionTarget;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 40)
    private String result;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "execute_after", nullable = false)
    private Instant executeAfter;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
