package com.gahyeonbot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "gahyeon_events", indexes = {
        @Index(name = "idx_gahyeon_events_session_sequence", columnList = "session_id,id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GahyeonEventRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Column(name = "scope_type", nullable = false, length = 30)
    private String scopeType;

    @Column(name = "scope_id", nullable = false, length = 200)
    private String scopeId;

    @Column(name = "session_id", length = 200)
    private String sessionId;

    @Column(name = "correlation_id", nullable = false, length = 120)
    private String correlationId;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
