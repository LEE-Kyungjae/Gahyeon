package com.gahyeonbot.adapters.desktop;

import com.gahyeonbot.application.life.CharacterDefinitionRegistry;
import com.gahyeonbot.application.life.CharacterLifeService;
import com.gahyeonbot.core.life.*;
import com.gahyeonbot.core.world.WorldId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/gahyeon/desktop/characters")
@ConditionalOnProperty(name = "gahyeon.headless.enabled", havingValue = "true")
@Validated
public class DesktopCharacterLifeController {
    private final CharacterDefinitionRegistry characters;
    private final CharacterLifeService life;

    public DesktopCharacterLifeController(CharacterDefinitionRegistry characters, CharacterLifeService life) {
        this.characters = characters;
        this.life = life;
    }

    @GetMapping
    public List<CharacterDefinition> characters() {
        return characters.all();
    }

    @GetMapping("/{characterId}/life")
    public CharacterLifeState current(
            @PathVariable @Size(max = 64) String characterId,
            @RequestParam(defaultValue = "gahyeon-home") @Size(max = 100) String worldId) {
        return life.current(new CharacterId(characterId), new WorldId(worldId));
    }

    @PostMapping("/{characterId}/life/stimuli")
    public LifeDecision observe(
            @PathVariable @Size(max = 64) String characterId,
            @RequestParam(defaultValue = "gahyeon-home") @Size(max = 100) String worldId,
            @Valid @RequestBody StimulusRequest request) {
        return life.observe(new CharacterId(characterId), new WorldId(worldId), new LifeStimulus(
                request.type(), request.importance(), request.subject(), request.expiresIfIgnored(), Instant.now()));
    }

    public record StimulusRequest(
            @NotBlank @Size(max = 80) String type,
            @DecimalMin("0.0") @DecimalMax("1.0") double importance,
            @Size(max = 200) String subject,
            boolean expiresIfIgnored
    ) {}
}
