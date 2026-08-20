package com.gahyeonbot.application.life;

import com.gahyeonbot.core.life.CharacterDefinition;
import com.gahyeonbot.core.life.LifeDecision;

/** Optional renderer output for one completed autonomous cognition decision. */
public interface CharacterCognitionPresentationPort {
    void present(CharacterDefinition character, LifeDecision decision, CharacterCognitionResult result);
}
