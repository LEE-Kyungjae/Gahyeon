package com.gahyeonbot.application.life;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gahyeon.life.enabled", havingValue = "true")
public class CharacterCognitionListener {
    private final CharacterCognitionService cognition;

    public CharacterCognitionListener(CharacterCognitionService cognition) {
        this.cognition = cognition;
    }

    @Async
    @EventListener
    public void handle(CharacterCognitionRequested event) {
        cognition.process(event.decision());
    }
}
