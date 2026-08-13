package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.identity.IdentityLinkUseCase;
import com.gahyeonbot.core.identity.ActorId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopIdentityLinkControllerTest {
    @Test
    void consumesTheOneTimeCodeForTheExactInstallation() {
        IdentityLinkUseCase links = mock(IdentityLinkUseCase.class);
        when(links.consumeDesktopLink("one-time", "install-1", "Zaeze"))
                .thenReturn(new IdentityLinkUseCase.LinkedDesktop(new ActorId(42), "credential"));
        var controller = new DesktopIdentityLinkController(links);

        var response = controller.link(new DesktopIdentityLinkController.LinkRequest(
                "one-time", "install-1", "Zaeze"));

        assertThat(response.getBody()).isEqualTo(
                new DesktopIdentityLinkController.LinkResponse(true, "credential"));
        verify(links).consumeDesktopLink("one-time", "install-1", "Zaeze");

        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(
                com.gahyeonbot.adapters.headless.GahyeonClientAuthenticationFilter.AUTHENTICATED_ACTOR_ATTRIBUTE,
                new ActorId(42));
        when(links.isDesktopLinked(new ActorId(42), "install-1")).thenReturn(true);
        var expiry = java.time.LocalDateTime.now().plusDays(90);
        when(links.desktopCredentialExpiresAt(new ActorId(42), "install-1"))
                .thenReturn(expiry);
        assertThat(controller.status("install-1", request).getBody())
                .isEqualTo(new DesktopIdentityLinkController.StatusResponse(true, expiry));
        assertThat(controller.status(
                "install-1", new org.springframework.mock.web.MockHttpServletRequest()).getBody())
                .isEqualTo(new DesktopIdentityLinkController.StatusResponse(false, null));
        when(links.revokeCurrentDesktop(new ActorId(42), "install-1")).thenReturn(true);
        assertThat(controller.unlinkCurrent("install-1", request).getStatusCode().value())
                .isEqualTo(204);
        verify(links).revokeCurrentDesktop(new ActorId(42), "install-1");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> controller.unlinkCurrent(
                        "install-1", new org.springframework.mock.web.MockHttpServletRequest())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
    }
}
