package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.adapters.headless.GahyeonClientAuthenticationFilter;
import com.gahyeonbot.application.identity.IdentityLinkUseCase;
import com.gahyeonbot.core.identity.ActorId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class DesktopCredentialAuthorization {
    private final IdentityLinkUseCase links;

    public DesktopCredentialAuthorization(IdentityLinkUseCase links) { this.links = links; }

    public void requireInstallation(HttpServletRequest request, String installationId) {
        Object authenticated = request.getAttribute(
                GahyeonClientAuthenticationFilter.AUTHENTICATED_ACTOR_ATTRIBUTE);
        if (!(authenticated instanceof ActorId actor)) return; // loopback/deployment compatibility
        ActorId owner = links.desktopActor(installationId);
        if (!actor.equals(owner)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Account credential does not own this Desktop installation");
        }
    }
}
