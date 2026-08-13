package com.gahyeonbot.services.tts;

/** Fail-closed signal: falling back would speak with an unapproved identity. */
public final class TtsIdentityMismatchException extends IllegalStateException {
    public TtsIdentityMismatchException(String message) {
        super(message);
    }
}
