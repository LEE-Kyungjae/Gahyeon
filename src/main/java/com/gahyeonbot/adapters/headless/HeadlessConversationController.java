package com.gahyeonbot.adapters.headless;

import com.gahyeonbot.application.identity.IdentityResolutionUseCase;
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
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/gahyeon/conversations")
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
@Validated
public class HeadlessConversationController {
    private final ConversationUseCase conversation;
    private final IdentityResolutionUseCase identities;

    public HeadlessConversationController(
            ConversationUseCase conversation,
            IdentityResolutionUseCase identities) {
        this.conversation = conversation;
        this.identities = identities;
    }

    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<MessageResponse> converse(
            @PathVariable @Size(max = ConversationSessionId.MAXIMUM_EXTERNAL_ID_CHARACTERS) String sessionId,
            @Valid @RequestBody MessageRequest body) {
        String requestId = body.requestId() == null || body.requestId().isBlank()
                ? "headless:" + UUID.randomUUID()
                : body.requestId();
        var session = new ConversationSession(
                ConversationSessionId.fromExternal(ClientSource.HEADLESS, sessionId),
                identities.resolveExternal(
                        IdentityProvider.HEADLESS,
                        body.resolvedExternalActorId(),
                        body.displayName(),
                        null),
                ClientSource.HEADLESS,
                ConversationModality.TEXT,
                Map.of());
        ConversationResponse response = conversation.converse(new ConversationRequest(
                requestId, session, body.displayName(), body.message()));
        return ResponseEntity.ok(new MessageResponse(response.runId(), response.content()));
    }

    public record MessageRequest(
            @Size(max = ConversationRequest.MAXIMUM_REQUEST_ID_CHARACTERS) String requestId,
            @Size(max = 200) String externalActorId,
            @Positive Long actorId,
            @Size(max = ConversationRequest.MAXIMUM_DISPLAY_NAME_CHARACTERS) String displayName,
            @NotBlank @Size(max = ConversationRequest.MAXIMUM_MESSAGE_CHARACTERS) String message
    ) {
        public MessageRequest {
            if ((externalActorId == null || externalActorId.isBlank()) && actorId == null) {
                throw new IllegalArgumentException("externalActorId가 필요합니다.");
            }
        }

        public MessageRequest(
                String requestId,
                String externalActorId,
                String displayName,
                String message) {
            this(requestId, externalActorId, null, displayName, message);
        }

        String resolvedExternalActorId() {
            return externalActorId == null || externalActorId.isBlank()
                    ? "legacy-numeric:" + actorId
                    : externalActorId.trim();
        }
    }

    public record MessageResponse(String runId, String content) {}
}
