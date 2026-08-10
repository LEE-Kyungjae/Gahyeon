package com.gahyeonbot.core.conversation;

public record AdmissionDecision(boolean accepted, Reason reason, String message) {
    public static AdmissionDecision allow() {
        return new AdmissionDecision(true, null, "");
    }

    public static AdmissionDecision reject(Reason reason, String message) {
        return new AdmissionDecision(false, reason, message);
    }

    public enum Reason {
        INVALID_INPUT,
        UNSAFE_INPUT,
        DUPLICATE,
        ACTOR_HOURLY_LIMIT,
        ACTOR_DAILY_LIMIT,
        GLOBAL_DAILY_LIMIT,
        GLOBAL_MONTHLY_LIMIT
    }
}
