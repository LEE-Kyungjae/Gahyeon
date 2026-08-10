package com.gahyeonbot.core.conversation;

public record AdmissionFacts(
        boolean moderationFlagged,
        boolean duplicate,
        long actorHourlyUsage,
        long actorDailyUsage,
        long totalDailyUsage,
        long totalMonthlyUsage) {
    public AdmissionFacts {
        if (actorHourlyUsage < 0 || actorDailyUsage < 0
                || totalDailyUsage < 0 || totalMonthlyUsage < 0) {
            throw new IllegalArgumentException("admission usage counts must not be negative");
        }
    }
}
