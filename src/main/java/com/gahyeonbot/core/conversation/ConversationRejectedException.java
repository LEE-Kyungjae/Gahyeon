package com.gahyeonbot.core.conversation;

public class ConversationRejectedException extends RuntimeException {
    public enum Reason {
        RATE_LIMITED,
        UNSAFE_INPUT
    }

    private final Reason reason;

    public ConversationRejectedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
