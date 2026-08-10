package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.core.world.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/gahyeon/desktop/worlds")
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
public class DesktopWorldController {
    private final WorldStateUseCase worlds;

    public DesktopWorldController(WorldStateUseCase worlds) {
        this.worlds = worlds;
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

    public record ErrorResponse(String code, String message) {}
}
