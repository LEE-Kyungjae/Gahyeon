package com.gahyeonbot.repository;

import com.gahyeonbot.entity.DesktopClientCredential;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface DesktopClientCredentialRepository extends JpaRepository<DesktopClientCredential, String> {
    @EntityGraph(attributePaths = "principal")
    Optional<DesktopClientCredential> findByCredentialHashAndRevokedAtIsNullAndExpiresAtAfter(
            String credentialHash, java.time.LocalDateTime now);
    Optional<DesktopClientCredential> findByInstallationIdAndRevokedAtIsNull(String installationId);
    Optional<DesktopClientCredential> findByInstallationId(String installationId);
    List<DesktopClientCredential> findTop10ByPrincipal_IdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            Long principalId, java.time.LocalDateTime now);
    Optional<DesktopClientCredential> findByIdAndPrincipal_IdAndRevokedAtIsNullAndExpiresAtAfter(
            String id, Long principalId, java.time.LocalDateTime now);
    long countByPrincipal_IdAndRevokedAtIsNullAndExpiresAtAfter(
            Long principalId, java.time.LocalDateTime now);
}
