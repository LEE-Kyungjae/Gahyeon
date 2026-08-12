package com.gahyeonbot.adapters.identity;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.identity.IdentityProvider;
import com.gahyeonbot.entity.Principal;
import com.gahyeonbot.repository.ExternalIdentityRepository;
import com.gahyeonbot.repository.IdentityLinkTokenRepository;
import com.gahyeonbot.repository.PrincipalRepository;
import com.gahyeonbot.repository.DesktopClientCredentialRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class JpaIdentityLinkAdapterTest {
    @Autowired PrincipalRepository principals;
    @Autowired ExternalIdentityRepository externalIdentities;
    @Autowired IdentityLinkTokenRepository tokens;
    @Autowired EntityManager entityManager;
    @Autowired DesktopClientCredentialRepository credentials;

    @Test
    void codeIsStoredOnlyAsHashAndCanLinkExactlyOneFreshDesktopInstallation() {
        principals.save(Principal.builder().id(42L).displayName("owner").build());
        externalIdentities.save(com.gahyeonbot.entity.ExternalIdentity.builder()
                .id(java.util.UUID.randomUUID().toString()).principal(principals.getReferenceById(42L))
                .provider(IdentityProvider.DISCORD).externalId("42").build());
        var links = new JpaIdentityLinkAdapter(principals, externalIdentities, tokens, entityManager, credentials);
        var issued = links.issueDesktopLink(new ActorId(42));

        assertThat(issued.code()).hasSize(22);
        assertThat(tokens.findById(issued.code())).isEmpty();
        assertThat(tokens.findAll()).singleElement()
                .extracting(token -> token.getTokenHash()).asString().hasSize(64);

        var linked = links.consumeDesktopLink(issued.code(), "fresh-install", "Desktop");
        assertThat(linked.actorId()).isEqualTo(new ActorId(42));
        assertThat(linked.credential()).hasSize(43);
        assertThat(credentials.findById(linked.credential())).isEmpty();
        assertThat(links.authenticateDesktopCredential(linked.credential()))
                .isEqualTo(new ActorId(42));
        var credentialRecord = credentials.findByInstallationId("fresh-install").orElseThrow();
        credentialRecord.setLastUsedAt(java.time.LocalDateTime.now().minusMinutes(10));
        credentials.saveAndFlush(credentialRecord);
        var staleLastUsed = credentialRecord.getLastUsedAt();
        links.authenticateDesktopCredential(linked.credential());
        assertThat(credentials.findByInstallationId("fresh-install").orElseThrow().getLastUsedAt())
                .isAfter(staleLastUsed);
        assertThat(externalIdentities.findByProviderAndExternalId(
                IdentityProvider.DESKTOP, "fresh-install"))
                .get().extracting(identity -> identity.getPrincipal().getId()).isEqualTo(42L);
        assertThat(links.isDesktopLinked(new ActorId(42), "fresh-install")).isTrue();
        assertThat(links.isDesktopLinked(new ActorId(77), "fresh-install")).isFalse();
        assertThat(links.isDesktopLinked(new ActorId(42), "missing-install")).isFalse();
        assertThatThrownBy(() -> links.consumeDesktopLink(
                issued.code(), "another-install", "Desktop"))
                .isInstanceOf(IllegalArgumentException.class);

        var rotated = links.consumeDesktopLink(
                links.issueDesktopLink(new ActorId(42)).code(), "fresh-install", "Desktop");
        assertThat(rotated.credential()).isNotEqualTo(linked.credential());
        assertThat(links.authenticateDesktopCredential(linked.credential())).isNull();
        assertThat(links.authenticateDesktopCredential(rotated.credential()))
                .isEqualTo(new ActorId(42));
        assertThat(credentials.count()).isOne();
        var device = links.listDesktopDevices(new ActorId(42)).get(0);
        assertThat(device.installationId()).isEqualTo("fresh-install");
        assertThat(device.label()).startsWith("Desktop ");
        assertThat(device.lastUsedAt()).isNotNull();
        assertThat(links.desktopCredentialExpiresAt(new ActorId(42), "fresh-install"))
                .isEqualTo(device.expiresAt());
        assertThat(links.desktopCredentialExpiresAt(new ActorId(77), "fresh-install"))
                .isNull();
        assertThat(links.renameDesktopDevice(new ActorId(77), device.id(), "Stolen"))
                .isFalse();
        assertThat(links.renameDesktopDevice(new ActorId(42), device.id(), "Main PC"))
                .isTrue();
        assertThat(links.listDesktopDevices(new ActorId(42)).get(0).label())
                .isEqualTo("Main PC");
        assertThat(links.renameDesktopDevice(new ActorId(42), device.id(), " "))
                .isFalse();
        assertThat(links.revokeDesktopDevice(new ActorId(77), device.id())).isFalse();
        assertThat(links.revokeDesktopDevice(new ActorId(42), device.id())).isTrue();
        assertThat(links.authenticateDesktopCredential(rotated.credential())).isNull();
        assertThat(links.listDesktopDevices(new ActorId(42))).isEmpty();
        assertThat(links.revokeDesktopDevice(new ActorId(42), device.id())).isFalse();

        var recovered = links.consumeDesktopLink(
                links.issueDesktopLink(new ActorId(42)).code(), "fresh-install", "Desktop");
        assertThat(links.authenticateDesktopCredential(recovered.credential()))
                .isEqualTo(new ActorId(42));
        assertThat(credentials.count()).isOne();
        for (int index = 2; index <= 10; index++) {
            links.consumeDesktopLink(links.issueDesktopLink(new ActorId(42)).code(),
                    "install-" + index, "Desktop");
        }
        assertThat(links.listDesktopDevices(new ActorId(42))).hasSize(10);
        var overflow = links.issueDesktopLink(new ActorId(42));
        assertThatThrownBy(() -> links.consumeDesktopLink(
                overflow.code(), "install-11", "Desktop"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("최대 10개");
        var expiring = credentials.findByInstallationId("fresh-install").orElseThrow();
        assertThat(expiring.getExpiresAt()).isAfter(expiring.getCreatedAt());
        expiring.setExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));
        credentials.saveAndFlush(expiring);
        assertThat(links.authenticateDesktopCredential(recovered.credential())).isNull();
        assertThat(links.isDesktopLinked(new ActorId(42), "fresh-install")).isFalse();
        assertThat(links.desktopCredentialExpiresAt(new ActorId(42), "fresh-install")).isNull();
        assertThat(links.listDesktopDevices(new ActorId(42))).hasSize(9);
        var afterExpiry = links.consumeDesktopLink(overflow.code(), "install-11", "Desktop");
        assertThat(links.listDesktopDevices(new ActorId(42))).hasSize(10);
        assertThat(links.revokeCurrentDesktop(new ActorId(77), "install-11")).isFalse();
        assertThat(links.revokeCurrentDesktop(new ActorId(42), "install-11")).isTrue();
        assertThat(links.authenticateDesktopCredential(afterExpiry.credential())).isNull();
    }

    @Test
    void mergesAnExistingDesktopPrincipalIntoTheAuthenticatedDiscordPrincipal() {
        principals.save(Principal.builder().id(42L).displayName("discord").build());
        externalIdentities.save(com.gahyeonbot.entity.ExternalIdentity.builder()
                .id(java.util.UUID.randomUUID().toString()).principal(principals.getReferenceById(42L))
                .provider(IdentityProvider.DISCORD).externalId("42").build());
        // Build the pre-existing identity directly; the link adapter must not steal it.
        Principal old = principals.save(Principal.builder().id(77L).displayName("old desktop").build());
        externalIdentities.save(com.gahyeonbot.entity.ExternalIdentity.builder()
                .id(java.util.UUID.randomUUID().toString()).principal(old)
                .provider(IdentityProvider.DESKTOP).externalId("used-install").build());
        var links = new JpaIdentityLinkAdapter(principals, externalIdentities, tokens, entityManager, credentials);
        var issued = links.issueDesktopLink(new ActorId(42));

        assertThat(links.consumeDesktopLink(issued.code(), "used-install", "Desktop").actorId())
                .isEqualTo(new ActorId(42));
        assertThat(externalIdentities.findByProviderAndExternalId(
                IdentityProvider.DESKTOP, "used-install"))
                .get().extracting(identity -> identity.getPrincipal().getId()).isEqualTo(42L);
    }

    @Test
    void expiredCodeCannotBeConsumedAndCleanupRemovesIt() {
        principals.save(Principal.builder().id(42L).displayName("owner").build());
        var links = new JpaIdentityLinkAdapter(principals, externalIdentities, tokens, entityManager, credentials);
        var issued = links.issueDesktopLink(new ActorId(42));
        var stored = tokens.findAll().get(0);
        stored.setExpiresAt(java.time.LocalDateTime.now().minusSeconds(1));
        tokens.saveAndFlush(stored);

        assertThatThrownBy(() -> links.consumeDesktopLink(
                issued.code(), "fresh-install", "Desktop"))
                .isInstanceOf(IllegalArgumentException.class);
        links.removeExpiredCodes();
        assertThat(tokens.count()).isZero();
    }
}
