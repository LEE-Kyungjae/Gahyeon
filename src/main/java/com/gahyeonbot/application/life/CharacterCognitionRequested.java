package com.gahyeonbot.application.life;

import com.gahyeonbot.core.life.LifeDecision;

public record CharacterCognitionRequested(LifeDecision decision) {
    public CharacterCognitionRequested {
        if (decision == null) throw new IllegalArgumentException("decision is required");
    }
}
