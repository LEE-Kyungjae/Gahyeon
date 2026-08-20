package com.gahyeonbot.application.life;

import java.util.List;

public interface CharacterMemoryConsolidationPort {
    boolean isReady();
    List<CharacterMemoryCandidate> consolidate(CharacterMemoryConsolidationRequest request);
}
