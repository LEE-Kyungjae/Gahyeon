package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.identity.IdentityResolutionService;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.core.conversation.ConversationUseCase;
import com.gahyeonbot.core.identity.IdentityProvider;
import com.gahyeonbot.core.session.ClientSource;
import com.gahyeonbot.core.session.ConversationModality;
import com.gahyeonbot.core.session.ConversationSession;
import com.gahyeonbot.core.session.ConversationSessionId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/gahyeon/desktop/conversations")
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
@Validated
public class DesktopConversationController {
    private final ConversationUseCase conversation;
    private final IdentityResolutionService identities;

    public DesktopConversationController(
            ConversationUseCase conversation,
            IdentityResolutionService identities) {
        this.conversation = conversation;
        this.identities = identities;
    }

    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<MessageResponse> converse(
            @PathVariable String sessionId,
            @Valid @RequestBody MessageRequest body) {
        var actorId = identities.resolveExternal(
                IdentityProvider.DESKTOP,
                body.installationId(),
                body.displayName(),
                null);
        var session = new ConversationSession(
                new ConversationSessionId(sessionId),
                actorId,
                ClientSource.DESKTOP,
                ConversationModality.TEXT,
                Map.of("desktop.installationId", body.installationId()));
        String requestId = body.requestId() == null || body.requestId().isBlank()
                ? "desktop:" + UUID.randomUUID()
                : body.requestId();
        ConversationResponse response = conversation.converse(new ConversationRequest(
                requestId, session, body.displayName(), body.message()));
        return ResponseEntity.ok(new MessageResponse(response.runId(), response.content()));
    }

    public record MessageRequest(
            String requestId,
            @NotBlank String installationId,
            String displayName,
            @NotBlank String message
    ) {}

    public record MessageResponse(String runId, String content) {}
}
