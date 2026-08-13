package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.identity.IdentityLinkUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import jakarta.servlet.http.HttpServletRequest;
import com.gahyeonbot.adapters.headless.GahyeonClientAuthenticationFilter;
import com.gahyeonbot.core.identity.ActorId;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/gahyeon/desktop/identity")
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
@Validated
public class DesktopIdentityLinkController {
    private final IdentityLinkUseCase links;

    public DesktopIdentityLinkController(IdentityLinkUseCase links) {
        this.links = links;
    }

    @PostMapping("/link")
    public ResponseEntity<LinkResponse> link(@Valid @RequestBody LinkRequest request) {
        var linked = links.consumeDesktopLink(
                request.code(), request.installationId(), request.displayName());
        return ResponseEntity.ok(new LinkResponse(true, linked.credential()));
    }

    @GetMapping("/status")
    public ResponseEntity<StatusResponse> status(
            @RequestParam @NotBlank @Size(max = 200) String installationId,
            HttpServletRequest request) {
        Object authenticated = request.getAttribute(
                GahyeonClientAuthenticationFilter.AUTHENTICATED_ACTOR_ATTRIBUTE);
        if (!(authenticated instanceof ActorId actor)) {
            return ResponseEntity.ok(new StatusResponse(false, null));
        }
        var expiresAt = links.desktopCredentialExpiresAt(actor, installationId);
        return ResponseEntity.ok(new StatusResponse(
                links.isDesktopLinked(actor, installationId), expiresAt));
    }

    @DeleteMapping("/current")
    public ResponseEntity<Void> unlinkCurrent(
            @RequestParam @NotBlank @Size(max = 200) String installationId,
            HttpServletRequest request) {
        Object authenticated = request.getAttribute(
                GahyeonClientAuthenticationFilter.AUTHENTICATED_ACTOR_ATTRIBUTE);
        if (!(authenticated instanceof ActorId actor)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Desktop account credential is required");
        }
        if (!links.revokeCurrentDesktop(actor, installationId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Authenticated account does not own this active Desktop credential");
        }
        return ResponseEntity.noContent().build();
    }

    public record LinkRequest(
            @NotBlank @Size(max = 128) String code,
            @NotBlank @Size(max = 200) String installationId,
            @Size(max = 100) String displayName) {}

    public record LinkResponse(boolean linked, String credential) {}
    public record StatusResponse(boolean linked, java.time.LocalDateTime credentialExpiresAt) {}
}
