package com.gahyeonbot.application.life;

import com.gahyeonbot.core.life.CharacterMemory;
import com.gahyeonbot.core.life.CharacterMemoryKind;
import com.gahyeonbot.core.life.LifeStimulus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Async;
import java.time.Clock;
import java.time.Duration;

@Component
public class CharacterConversationMemoryListener {
    private final CharacterDefinitionRegistry characters;
    private final CharacterMemoryStore memories;
    private final CharacterLifeService life;
    private final Clock clock;

    @Autowired
    public CharacterConversationMemoryListener(CharacterDefinitionRegistry characters,
            CharacterMemoryStore memories, CharacterLifeService life) {
        this(characters, memories, life, Clock.systemUTC());
    }

    CharacterConversationMemoryListener(CharacterDefinitionRegistry characters,
            CharacterMemoryStore memories, CharacterLifeService life, Clock clock) {
        this.characters = characters;
        this.memories = memories;
        this.life = life;
        this.clock = clock;
    }

    @EventListener
    @Async
    public void handle(CharacterConversationCompleted event) {
        CharacterConversationContext.from(event.request().session()).ifPresent(context -> {
            characters.require(context.characterId());
            var now = clock.instant();
            memories.append(new CharacterMemory(0, context.characterId(), context.worldId(), context.subjectId(),
                    CharacterMemoryKind.WORKING, "user: " + event.request().message(),
                    0.55, 1, 0, now.plus(Duration.ofDays(7)), now, now));
            memories.append(new CharacterMemory(0, context.characterId(), context.worldId(), context.subjectId(),
                    CharacterMemoryKind.WORKING, "assistant: " + event.response().content(),
                    0.50, 1, 0, now.plus(Duration.ofDays(7)), now, now));
            var state = life.current(context.characterId(), context.worldId());
            String stimulusType = state != null && state.prospectiveIntention() != null
                    ? "user.returned" : "user.interaction";
            life.observe(context.characterId(), context.worldId(), new LifeStimulus(
                    stimulusType, 0.70, "user", false, now));
        });
    }
}
