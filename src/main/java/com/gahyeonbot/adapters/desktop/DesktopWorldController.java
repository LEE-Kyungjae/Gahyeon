package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.behavior.WorldActionCoordinator;
import com.gahyeonbot.core.world.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/gahyeon/desktop/worlds")
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
@Validated
public class DesktopWorldController {
    private final WorldStateUseCase worlds;
    private final WorldActionCoordinator actions;
    private final DesktopCredentialAuthorization credentialAuthorization;
    private final DesktopWorldActionPresentationPresence presentationPresence;

    public DesktopWorldController(
            WorldStateUseCase worlds,
            WorldActionCoordinator actions,
            DesktopCredentialAuthorization credentialAuthorization,
            DesktopWorldActionPresentationPresence presentationPresence) {
        this.worlds = worlds;
        this.actions = actions;
        this.credentialAuthorization = credentialAuthorization;
        this.presentationPresence = presentationPresence;
    }

    @GetMapping("/{worldId}")
    public WorldStateSnapshot current(@PathVariable String worldId) {
        return worlds.current(new WorldId(worldId));
    }

    @PostMapping("/{worldId}/move")
    public WorldStateSnapshot move(
            @PathVariable String worldId,
            @Valid @RequestBody MoveRequest request) {
        return worlds.move(
                new WorldId(worldId),
                request.expectedRevision(),
                request.room(),
                new WorldPosition(request.x(), request.y(), request.z()));
    }

    @PostMapping("/{worldId}/activity")
    public WorldStateSnapshot activity(
            @PathVariable String worldId,
            @Valid @RequestBody ActivityRequest request) {
        return worlds.changeActivity(
                new WorldId(worldId),
                request.expectedRevision(),
                request.activity(),
                request.interactionTarget());
    }

    @PostMapping("/{worldId}/emotion")
    public WorldStateSnapshot emotion(
            @PathVariable String worldId,
            @Valid @RequestBody EmotionRequest request) {
        return worlds.changeEmotion(
                new WorldId(worldId),
                request.expectedRevision(),
                request.emotion(),
                request.intensity());
    }

    @PostMapping("/{worldId}/actions/{actionId}/complete")
    public CompleteActionResponse completeAction(
            @PathVariable String worldId,
            @PathVariable @Size(max = 80) String actionId,
            @Valid @RequestBody CompleteActionRequest request,
            HttpServletRequest httpRequest) {
        credentialAuthorization.requireInstallation(httpRequest, request.installationId());
        var result = actions.complete(new WorldActionCoordinator.ActionCompletion(
                new WorldId(worldId),
                actionId,
                request.expectedRevision(),
                "completed",
                "desktop_presentation_completed",
                new WorldPosition(request.x(), request.y(), request.z())));
        return new CompleteActionResponse(result);
    }

    @PostMapping("/{worldId}/presence")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeatPresence(
            @PathVariable @NotBlank
            @Size(max = DesktopEventStreamService.MAXIMUM_WORLD_ID_CHARACTERS)
            String worldId,
            @Valid @RequestBody PresenceRequest request,
            HttpServletRequest httpRequest) {
        credentialAuthorization.requireInstallation(httpRequest, request.installationId());
        presentationPresence.heartbeat(
                new WorldId(worldId), request.installationId(), request.rendererId());
    }

    @DeleteMapping("/{worldId}/presence")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releasePresence(
            @PathVariable @NotBlank
            @Size(max = DesktopEventStreamService.MAXIMUM_WORLD_ID_CHARACTERS)
            String worldId,
            @RequestParam @NotBlank @Size(max = 200) String installationId,
            @RequestParam @NotBlank
            @Size(max = DesktopWorldActionPresentationPresence.MAXIMUM_RENDERER_ID_CHARACTERS)
            String rendererId,
            HttpServletRequest httpRequest) {
        credentialAuthorization.requireInstallation(httpRequest, installationId);
        presentationPresence.release(new WorldId(worldId), installationId, rendererId);
    }

    @ExceptionHandler(WorldStateConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(WorldStateConflictException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("world_revision_conflict", error.getMessage()));
    }

    public record MoveRequest(
            @PositiveOrZero long expectedRevision,
            @NotBlank String room,
            double x,
            double y,
            double z
    ) {}

    public record ActivityRequest(
            @PositiveOrZero long expectedRevision,
            @NotNull WorldActivity activity,
            String interactionTarget
    ) {}

    public record EmotionRequest(
            @PositiveOrZero long expectedRevision,
            @NotBlank String emotion,
            double intensity
    ) {}

    public record CompleteActionRequest(
            @NotBlank @Size(max = 200) String installationId,
            @PositiveOrZero long expectedRevision,
            double x,
            double y,
            double z
    ) {}

    public record PresenceRequest(
            @NotBlank @Size(max = 200) String installationId,
            @NotBlank
            @Size(max = DesktopWorldActionPresentationPresence.MAXIMUM_RENDERER_ID_CHARACTERS)
            String rendererId
    ) {}

    public record CompleteActionResponse(WorldActionCoordinator.CompletionResult result) {}

    public record ErrorResponse(String code, String message) {}
}
