package com.gahyeonbot.adapters.discord.voice;

final class TtsSpeechText {
    private TtsSpeechText() {}

    static String sanitize(String value) {
        if (value == null || value.isBlank()) return "";
        return value
                .replaceAll("(?s)```(?:[a-zA-Z0-9_+-]+)?\\s*(.*?)```", "$1")
                .replaceAll("\\[([^]]+)]\\(https?://[^)]+\\)", "$1")
                .replaceAll("https?://\\S+", "")
                .replaceAll("<a?:[a-zA-Z0-9_]+:\\d+>", "")
                .replaceAll("<[@#&]!?(?:\\d+)>", "")
                .replaceAll("[`*_~>#|]", " ")
                .replaceAll("[\\p{So}\\p{Sk}]", " ")
                .replaceAll("[^\\p{L}\\p{N}\\s.,?!%+\\-:/]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static boolean isSafeToSpeak(String value) {
        if (value == null || value.isBlank()) return false;
        return !value.matches("(?is).*\\b(exception|stack\\s*trace|too_many_requests)\\b.*")
                && !value.matches("(?is).*\\b(http|status)\\s*[:=]?\\s*[45]\\d\\d\\b.*")
                && !value.matches("(?is).*\\berror\\s*[:=].*")
                && !value.matches("(?s).*(오류|에러)\\s*(코드|단계)\\s*[:=]?.*");
    }
}
