package com.gahyeonbot.adapters.identity;

import com.gahyeonbot.application.identity.IdentityLinkUseCase;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.identity.IdentityProvider;
import com.gahyeonbot.entity.ExternalIdentity;
import com.gahyeonbot.entity.IdentityLinkToken;
import com.gahyeonbot.entity.DesktopClientCredential;
import com.gahyeonbot.repository.ExternalIdentityRepository;
import com.gahyeonbot.repository.IdentityLinkTokenRepository;
import com.gahyeonbot.repository.PrincipalRepository;
import com.gahyeonbot.repository.DesktopClientCredentialRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

@Component
public class JpaIdentityLinkAdapter implements IdentityLinkUseCase {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_BYTES = 16;
    private static final int EXPIRY_MINUTES = 10;
    private static final int LAST_USED_WRITE_INTERVAL_MINUTES = 5;
    private static final int MAXIMUM_ACTIVE_DESKTOP_DEVICES = 10;
    private static final int DESKTOP_CREDENTIAL_LIFETIME_DAYS = 90;

    private final PrincipalRepository principals;
    private final ExternalIdentityRepository externalIdentities;
    private final IdentityLinkTokenRepository tokens;
    private final EntityManager entityManager;
    private final DesktopClientCredentialRepository credentials;

    public JpaIdentityLinkAdapter(PrincipalRepository principals,
                                  ExternalIdentityRepository externalIdentities,
                                  IdentityLinkTokenRepository tokens,
                                  EntityManager entityManager,
                                  DesktopClientCredentialRepository credentials) {
        this.principals = principals;
        this.externalIdentities = externalIdentities;
        this.tokens = tokens;
        this.entityManager = entityManager;
        this.credentials = credentials;
    }

    @Override
    @Transactional
    public IssuedLink issueDesktopLink(ActorId actorId) {
        var principal = principals.findById(actorId.value())
                .orElseThrow(() -> new IllegalArgumentException("연결할 Gahyeon 계정이 없습니다."));
        byte[] entropy = new byte[CODE_BYTES];
        RANDOM.nextBytes(entropy);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        LocalDateTime now = LocalDateTime.now();
        tokens.save(IdentityLinkToken.builder()
                .tokenHash(hash(code)).principal(principal)
                .targetProvider(IdentityProvider.DESKTOP)
                .createdAt(now).expiresAt(now.plusMinutes(EXPIRY_MINUTES)).build());
        return new IssuedLink(code, now.plusMinutes(EXPIRY_MINUTES));
    }

    @Override
    @Transactional
    public LinkedDesktop consumeDesktopLink(String code, String installationId, String displayName) {
        if (code == null || code.isBlank() || code.length() > 128) throw invalid();
        if (installationId == null || installationId.isBlank() || installationId.length() > 200) throw invalid();
        var token = tokens.findForConsume(hash(code.trim())).orElseThrow(JpaIdentityLinkAdapter::invalid);
        LocalDateTime now = LocalDateTime.now();
        if (token.getConsumedAt() != null || !token.getExpiresAt().isAfter(now)
                || token.getTargetProvider() != IdentityProvider.DESKTOP) throw invalid();
        var existing = externalIdentities.findByProviderAndExternalId(
                IdentityProvider.DESKTOP, installationId.trim());
        if (existing.isPresent() && !existing.get().getPrincipal().getId()
                .equals(token.getPrincipal().getId())) {
            mergePrincipal(existing.get().getPrincipal().getId(), token.getPrincipal().getId());
        }
        var previousCredential = credentials.findByInstallationId(installationId.trim());
        if (previousCredential.isEmpty()
                && credentials.countByPrincipal_IdAndRevokedAtIsNullAndExpiresAtAfter(
                        token.getPrincipal().getId(), now) >= MAXIMUM_ACTIVE_DESKTOP_DEVICES) {
            throw new IllegalStateException("활성 Desktop 기기는 최대 10개까지 연결할 수 있습니다.");
        }
        if (existing.isEmpty()) {
            externalIdentities.saveAndFlush(ExternalIdentity.builder()
                    .id(UUID.randomUUID().toString())
                    .principal(token.getPrincipal())
                    .provider(IdentityProvider.DESKTOP)
                    .externalId(installationId.trim())
                    .build());
        }
        token.setConsumedAt(now);
        tokens.save(token);
        previousCredential.ifPresent(existingCredential -> {
            credentials.delete(existingCredential);
            credentials.flush();
        });
        byte[] credentialEntropy = new byte[32];
        RANDOM.nextBytes(credentialEntropy);
        String credential = Base64.getUrlEncoder().withoutPadding().encodeToString(credentialEntropy);
        credentials.save(DesktopClientCredential.builder()
                .id(UUID.randomUUID().toString()).credentialHash(hash(credential))
                .principal(token.getPrincipal()).installationId(installationId.trim())
                .deviceLabel(defaultDeviceLabel(installationId.trim()))
                .createdAt(now).lastUsedAt(now)
                .expiresAt(now.plusDays(DESKTOP_CREDENTIAL_LIFETIME_DAYS)).build());
        return new LinkedDesktop(new ActorId(token.getPrincipal().getId()), credential);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isDesktopLinked(ActorId actorId, String installationId) {
        if (actorId == null || installationId == null || installationId.isBlank()
                || installationId.length() > 200) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        return credentials.findByInstallationIdAndRevokedAtIsNull(installationId.trim())
                .filter(value -> value.getExpiresAt().isAfter(now))
                .filter(value -> value.getPrincipal().getId().equals(actorId.value()))
                .isPresent();
    }

    @Override
    @Transactional
    public ActorId authenticateDesktopCredential(String credential) {
        if (credential == null || credential.isBlank() || credential.length() > 256) return null;
        LocalDateTime now = LocalDateTime.now();
        return credentials.findByCredentialHashAndRevokedAtIsNullAndExpiresAtAfter(
                        hash(credential.trim()), now)
                .map(value -> {
                    if (value.getLastUsedAt() == null || value.getLastUsedAt().isBefore(
                            now.minusMinutes(LAST_USED_WRITE_INTERVAL_MINUTES))) {
                        value.setLastUsedAt(now);
                        credentials.save(value);
                    }
                    return new ActorId(value.getPrincipal().getId());
                }).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public ActorId desktopActor(String installationId) {
        if (installationId == null || installationId.isBlank() || installationId.length() > 200) return null;
        return externalIdentities.findByProviderAndExternalId(
                        IdentityProvider.DESKTOP, installationId.trim())
                .map(value -> new ActorId(value.getPrincipal().getId())).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<DesktopDevice> listDesktopDevices(ActorId actorId) {
        if (actorId == null) throw new IllegalArgumentException("Gahyeon 계정이 필요합니다.");
        return credentials.findTop10ByPrincipal_IdAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                        actorId.value(), LocalDateTime.now())
                .stream().map(value -> new DesktopDevice(
                        value.getId(), value.getInstallationId(), value.getDeviceLabel(),
                        value.getCreatedAt(), value.getLastUsedAt(), value.getExpiresAt())).toList();
    }

    @Override
    @Transactional
    public boolean revokeDesktopDevice(ActorId actorId, String deviceId) {
        if (actorId == null || deviceId == null || deviceId.isBlank() || deviceId.length() > 36) {
            return false;
        }
        return credentials.findByIdAndPrincipal_IdAndRevokedAtIsNullAndExpiresAtAfter(
                        deviceId.trim(), actorId.value(), LocalDateTime.now())
                .map(value -> {
                    value.setRevokedAt(LocalDateTime.now());
                    credentials.save(value);
                    return true;
                }).orElse(false);
    }

    @Override
    @Transactional
    public boolean renameDesktopDevice(ActorId actorId, String deviceId, String label) {
        if (actorId == null || deviceId == null || deviceId.isBlank() || deviceId.length() > 36
                || label == null || label.isBlank() || label.trim().length() > 100) return false;
        return credentials.findByIdAndPrincipal_IdAndRevokedAtIsNullAndExpiresAtAfter(
                        deviceId.trim(), actorId.value(), LocalDateTime.now())
                .map(value -> {
                    value.setDeviceLabel(label.trim());
                    credentials.save(value);
                    return true;
                }).orElse(false);
    }

    @Override
    @Transactional
    public boolean revokeCurrentDesktop(ActorId actorId, String installationId) {
        if (actorId == null || installationId == null || installationId.isBlank()
                || installationId.length() > 200) return false;
        return credentials.findByInstallationIdAndRevokedAtIsNull(installationId.trim())
                .filter(value -> value.getExpiresAt().isAfter(LocalDateTime.now()))
                .filter(value -> value.getPrincipal().getId().equals(actorId.value()))
                .map(value -> {
                    value.setRevokedAt(LocalDateTime.now());
                    credentials.save(value);
                    return true;
                }).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public LocalDateTime desktopCredentialExpiresAt(ActorId actorId, String installationId) {
        if (actorId == null || installationId == null || installationId.isBlank()
                || installationId.length() > 200) return null;
        LocalDateTime now = LocalDateTime.now();
        return credentials.findByInstallationIdAndRevokedAtIsNull(installationId.trim())
                .filter(value -> value.getPrincipal().getId().equals(actorId.value()))
                .filter(value -> value.getExpiresAt().isAfter(now))
                .map(DesktopClientCredential::getExpiresAt).orElse(null);
    }

    private static String defaultDeviceLabel(String installationId) {
        String suffix = installationId.length() <= 12
                ? installationId : installationId.substring(installationId.length() - 12);
        return "Desktop " + suffix;
    }

    private void mergePrincipal(long sourceId, long targetId) {
        // These are the platform-neutral actor-owned records. Discord-only DM tables
        // intentionally remain keyed by the Discord user ID and are not rewritten.
        for (String table : new String[]{"conversation_history"}) {
            entityManager.createNativeQuery(
                    "UPDATE " + table + " SET user_id = :target WHERE user_id = :source")
                    .setParameter("target", targetId).setParameter("source", sourceId)
                    .executeUpdate();
        }
        for (String table : new String[]{"agent_sessions", "agent_runs"}) {
            entityManager.createNativeQuery(
                    "UPDATE " + table + " SET user_id = :target WHERE user_id = :source")
                    .setParameter("target", targetId).setParameter("source", sourceId)
                    .executeUpdate();
        }
        entityManager.createNativeQuery(
                "UPDATE openai_usage SET user_id = :target WHERE user_id = :source")
                .setParameter("target", targetId).setParameter("source", sourceId).executeUpdate();
        entityManager.createNativeQuery(
                "UPDATE external_identities SET principal_id = :target WHERE principal_id = :source")
                .setParameter("target", targetId).setParameter("source", sourceId).executeUpdate();
        entityManager.createNativeQuery(
                "UPDATE identity_link_tokens SET principal_id = :target WHERE principal_id = :source")
                .setParameter("target", targetId).setParameter("source", sourceId).executeUpdate();
        entityManager.createNativeQuery(
                "UPDATE desktop_client_credentials SET principal_id = :target WHERE principal_id = :source")
                .setParameter("target", targetId).setParameter("source", sourceId).executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("연결 코드가 잘못되었거나 만료되었습니다.");
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Scheduled(fixedDelayString = "${gahyeon.identity-link.cleanup-millis:3600000}")
    @Transactional
    public void removeExpiredCodes() {
        tokens.deleteExpiredBefore(LocalDateTime.now());
    }
}
