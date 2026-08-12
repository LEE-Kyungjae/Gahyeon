package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.adapters.headless.GahyeonClientAuthenticationFilter;
import com.gahyeonbot.application.identity.IdentityLinkUseCase;
import com.gahyeonbot.core.identity.ActorId;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DesktopCredentialAuthorizationTest {
    @Test
    void credentialActorMustOwnTheClaimedInstallation() {
        IdentityLinkUseCase links = mock(IdentityLinkUseCase.class);
        when(links.desktopActor("owned")).thenReturn(new ActorId(42));
        when(links.desktopActor("foreign")).thenReturn(new ActorId(77));
        var authorization = new DesktopCredentialAuthorization(links);
        var request = new MockHttpServletRequest();
        request.setAttribute(GahyeonClientAuthenticationFilter.AUTHENTICATED_ACTOR_ATTRIBUTE,
                new ActorId(42));

        authorization.requireInstallation(request, "owned");
        assertThatThrownBy(() -> authorization.requireInstallation(request, "foreign"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
    }
}
