package com.gahyeonbot.entity;

import com.gahyeonbot.services.ai.agent.AgentModality;
import com.gahyeonbot.services.ai.agent.AgentRunStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_runs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRun {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "request_id", nullable = false, unique = true, length = 120)
    private String requestId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AgentSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway", nullable = false, length = 20)
    private AgentModality modality;

    @Column(name = "guild_id")
    private Long toolScopeId;

    @Column(name = "user_id", nullable = false)
    private Long actorId;

    @Column(name = "username", nullable = false, length = 100)
    private String actorDisplayName;

    @Column(name = "input_text", nullable = false, columnDefinition = "TEXT")
    private String inputText;

    @Column(name = "output_text", columnDefinition = "TEXT")
    private String outputText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AgentRunStatus status;

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    @Column(name = "max_steps", nullable = false)
    private int maxSteps;

    @Column(name = "next_event_sequence", nullable = false)
    private long nextEventSequence;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(nullable = false)
    private long version;
}
