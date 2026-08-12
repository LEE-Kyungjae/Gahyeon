package com.gahyeonbot.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "desktop_client_credentials", uniqueConstraints = {
        @UniqueConstraint(name = "uq_desktop_client_credential_hash", columnNames = "credential_hash"),
        @UniqueConstraint(name = "uq_desktop_client_credential_installation", columnNames = "installation_id")})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DesktopClientCredential {
    @Id @Column(length = 36) private String id;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "credential_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String credentialHash;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "principal_id", nullable = false) private Principal principal;
    @Column(name = "installation_id", nullable = false, length = 200) private String installationId;
    @Column(name = "device_label", length = 100) private String deviceLabel;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "last_used_at") private LocalDateTime lastUsedAt;
    @Column(name = "expires_at", nullable = false) private LocalDateTime expiresAt;
    @Column(name = "revoked_at") private LocalDateTime revokedAt;
}
