package com.gahyeonbot.application.life;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class CharacterMemoryConsolidationListener {
    private final CharacterMemoryConsolidationService service;

    public CharacterMemoryConsolidationListener(CharacterMemoryConsolidationService service) {
        this.service = service;
    }

    @Async
    @EventListener
    public void handle(CharacterConversationCompleted event) {
        service.consolidate(event);
    }
}
