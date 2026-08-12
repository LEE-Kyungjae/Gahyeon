package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.identity.IdentityResolutionUseCase;
import com.gahyeonbot.application.conversation.ConversationStreamObserver;
import com.gahyeonbot.application.conversation.ConversationStreamingUseCase;
import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.core.identity.IdentityProvider;
import com.gahyeonbot.core.session.ClientSource;
import com.gahyeonbot.core.session.ConversationModality;
import com.gahyeonbot.core.session.ConversationSession;
import com.gahyeonbot.core.session.ConversationSessionId;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/gahyeon/desktop/conversations")
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
@Validated
public class DesktopConversationController {
    private final ConversationStreamingUseCase conversation;
    private final IdentityResolutionUseCase identities;
    private final DesktopEventStreamService streams;
    private final DesktopSessionOwnership ownership;
    private final DesktopCredentialAuthorization credentialAuthorization;

    public DesktopConversationController(
            ConversationStreamingUseCase conversation,
            IdentityResolutionUseCase identities,
            DesktopEventStreamService streams,
            DesktopSessionOwnership ownership,
            DesktopCredentialAuthorization credentialAuthorization) {
        this.conversation = conversation;
        this.identities = identities;
        this.streams = streams;
        this.ownership = ownership;
        this.credentialAuthorization = credentialAuthorization;
    }

    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<MessageResponse> converse(
            @PathVariable @Size(max = ConversationSessionId.MAXIMUM_EXTERNAL_ID_CHARACTERS)
            String sessionId,
            @Valid @RequestBody MessageRequest body,
            HttpServletRequest request) {
        credentialAuthorization.requireInstallation(request, body.installationId());
        ownership.claim(sessionId, body.installationId());
        var actorId = identities.resolveExternal(
                IdentityProvider.DESKTOP,
                body.installationId(),
                body.displayName(),
                null);
        var session = new ConversationSession(
                ConversationSessionId.fromExternal(ClientSource.DESKTOP, sessionId),
                actorId,
                ClientSource.DESKTOP,
                ConversationModality.TEXT,
                Map.of("desktop.installationId", body.installationId()));
        String requestId = body.requestId() == null || body.requestId().isBlank()
                ? "desktop:" + UUID.randomUUID()
                : body.requestId();
        String generation = streams.beginConversation(sessionId);
        try {
            ConversationResponse response = conversation.converseStreaming(new ConversationRequest(
                    requestId, session, body.displayName(), body.message()), new ConversationStreamObserver() {
                @Override
                public void onTextDelta(String delta) {
                    if (streams.isCurrentConversation(sessionId, generation)) {
                        streams.publishConversationDelta(sessionId, requestId, delta);
                    }
                }

                @Override
                public boolean isCancelled() {
                    return !streams.isCurrentConversation(sessionId, generation);
                }
            });
            return ResponseEntity.ok(new MessageResponse(response.runId(), response.content()));
        } finally {
            streams.finishConversation(sessionId, generation);
        }
    }

    @DeleteMapping("/{sessionId}/active")
    public ResponseEntity<Void> cancel(
            @PathVariable @Size(max = ConversationSessionId.MAXIMUM_EXTERNAL_ID_CHARACTERS)
            String sessionId,
            @jakarta.validation.constraints.NotBlank @RequestParam @Size(max = 200)
            String installationId,
            HttpServletRequest request) {
        credentialAuthorization.requireInstallation(request, installationId);
        ownership.requireOwner(sessionId, installationId);
        streams.cancelConversation(sessionId);
        return ResponseEntity.noContent().build();
    }

    public record MessageRequest(
            @Size(max = ConversationRequest.MAXIMUM_REQUEST_ID_CHARACTERS) String requestId,
            @NotBlank @Size(max = 200) String installationId,
            @Size(max = ConversationRequest.MAXIMUM_DISPLAY_NAME_CHARACTERS) String displayName,
            @NotBlank @Size(max = ConversationRequest.MAXIMUM_MESSAGE_CHARACTERS) String message
    ) {}

    public record MessageResponse(String runId, String content) {}
}
