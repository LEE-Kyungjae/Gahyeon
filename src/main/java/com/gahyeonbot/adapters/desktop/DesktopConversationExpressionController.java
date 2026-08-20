package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.speech.ConversationExpressionPlanningService;
import com.gahyeonbot.application.identity.IdentityResolutionUseCase;
import com.gahyeonbot.core.identity.IdentityProvider;
import com.gahyeonbot.core.life.CharacterId;
import com.gahyeonbot.core.speech.VoiceExpression;
import com.gahyeonbot.core.world.WorldId;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/gahyeon/desktop/speech/expression-plans")
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
public final class DesktopConversationExpressionController {
    private final ConversationExpressionPlanningService planning;
    private final IdentityResolutionUseCase identities;
    private final DesktopCredentialAuthorization credentialAuthorization;

    public DesktopConversationExpressionController(
            ConversationExpressionPlanningService planning,
            IdentityResolutionUseCase identities,
            DesktopCredentialAuthorization credentialAuthorization) {
        this.planning = planning;
        this.identities = identities;
        this.credentialAuthorization = credentialAuthorization;
    }

    @PostMapping
    public VoiceExpression plan(@Valid @RequestBody PlanRequest request, HttpServletRequest servletRequest) {
        credentialAuthorization.requireInstallation(servletRequest, request.installationId());
        var actorId = identities.resolveExternal(
                IdentityProvider.DESKTOP, request.installationId(), request.displayName(), null);
        return planning.plan(
                new CharacterId(request.characterId()),
                new WorldId(request.worldId()),
                actorId.toString(),
                request.message());
    }

    public record PlanRequest(
            @NotBlank @Size(max = 200) String installationId,
            @Size(max = 100) String displayName,
            @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9][a-z0-9._-]*") String characterId,
            @NotBlank @Size(max = 120) String worldId,
            @NotBlank @Size(max = 16_384) String message
    ) {}
}
