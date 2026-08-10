package com.gahyeonbot.entity;

import com.gahyeonbot.core.identity.IdentityProvider;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "external_identities",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_external_identity_provider_id",
                columnNames = {"provider", "external_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalIdentity {
    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "principal_id", nullable = false)
    private Principal principal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IdentityProvider provider;

    @Column(name = "external_id", nullable = false, length = 200)
    private String externalId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
