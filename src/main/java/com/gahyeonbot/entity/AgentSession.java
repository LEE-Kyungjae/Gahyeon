package com.gahyeonbot.entity;

import com.gahyeonbot.services.ai.agent.AgentModality;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSession {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "session_key", nullable = false, unique = true, length = 200)
    private String sessionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway", nullable = false, length = 20)
    private AgentModality modality;

    @Column(name = "guild_id")
    private Long toolScopeId;

    @Column(name = "user_id", nullable = false)
    private Long actorId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
