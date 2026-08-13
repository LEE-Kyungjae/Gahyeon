package com.gahyeonbot.entity;

import com.gahyeonbot.core.identity.IdentityProvider;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "identity_link_tokens")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class IdentityLinkToken {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", length = 64, columnDefinition = "CHAR(64)")
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "principal_id", nullable = false)
    private Principal principal;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_provider", nullable = false, length = 30)
    private IdentityProvider targetProvider;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
