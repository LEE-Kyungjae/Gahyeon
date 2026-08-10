package com.gahyeonbot.application.identity;

import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.identity.IdentityProvider;
import com.gahyeonbot.repository.ExternalIdentityRepository;
import com.gahyeonbot.repository.PrincipalRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class IdentityResolutionServiceTest {
    @Autowired PrincipalRepository principals;
    @Autowired ExternalIdentityRepository externalIdentities;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void persistsAndReusesDiscordIdentityWithoutChangingLegacyActorId() {
        var service = new IdentityResolutionService(
                principals, externalIdentities, transactionManager);

        ActorId first = service.resolveExternal(
                IdentityProvider.DISCORD, "123456789", "first name", 123456789L);
        ActorId second = service.resolveExternal(
                IdentityProvider.DISCORD, "123456789", "changed name", 123456789L);

        assertThat(first).isEqualTo(new ActorId(123456789L));
        assertThat(second).isEqualTo(first);
        assertThat(principals.count()).isOne();
        assertThat(externalIdentities.count()).isOne();
        assertThat(externalIdentities.findByProviderAndExternalId(
                IdentityProvider.DISCORD, "123456789"))
                .get()
                .extracting(identity -> identity.getPrincipal().getId())
                .isEqualTo(123456789L);
    }

    @Test
    void allocatesAndReusesAnInternalIdentityForDesktopInstallation() {
        var service = new IdentityResolutionService(
                principals, externalIdentities, transactionManager);

        ActorId first = service.resolveExternal(
                IdentityProvider.DESKTOP, "installation-abc", "Desktop user", null);
        ActorId second = service.resolveExternal(
                IdentityProvider.DESKTOP, "installation-abc", "Renamed user", null);

        assertThat(first.value()).isPositive();
        assertThat(second).isEqualTo(first);
        assertThat(principals.count()).isOne();
        assertThat(externalIdentities.findByProviderAndExternalId(
                IdentityProvider.DESKTOP, "installation-abc"))
                .get()
                .extracting(identity -> identity.getPrincipal().getId())
                .isEqualTo(first.value());
    }
}
