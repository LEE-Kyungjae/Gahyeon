package com.gahyeonbot.adapters.headless;

import com.gahyeonbot.core.conversation.ConversationRequest;
import com.gahyeonbot.core.conversation.ConversationResponse;
import com.gahyeonbot.core.conversation.ConversationUseCase;
import com.gahyeonbot.core.identity.ActorId;
import com.gahyeonbot.core.session.ClientSource;
import com.gahyeonbot.core.session.ConversationModality;
import com.gahyeonbot.core.session.ConversationSession;
import com.gahyeonbot.core.session.ConversationSessionId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
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

    public HeadlessConversationController(ConversationUseCase conversation) {
        this.conversation = conversation;
    }

    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<MessageResponse> converse(
            @PathVariable String sessionId,
            @Valid @RequestBody MessageRequest body) {
        String requestId = body.requestId() == null || body.requestId().isBlank()
                ? "headless:" + UUID.randomUUID()
                : body.requestId();
        var session = new ConversationSession(
                new ConversationSessionId(sessionId),
                new ActorId(body.actorId()),
                ClientSource.HEADLESS,
                ConversationModality.TEXT,
                Map.of());
        ConversationResponse response = conversation.converse(new ConversationRequest(
                requestId, session, body.displayName(), body.message()));
        return ResponseEntity.ok(new MessageResponse(response.runId(), response.content()));
    }

    public record MessageRequest(
            String requestId,
            @Positive long actorId,
            String displayName,
            @NotBlank String message
    ) {}

    public record MessageResponse(String runId, String content) {}
}
