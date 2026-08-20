package com.gahyeonbot.application.life;

public interface CharacterCognitionPort {
    boolean isReady();
    CharacterCognitionResult generate(CharacterCognitionRequest request);
}
