package com.gahyeonbot.services.ai.agent;

import java.util.Locale;

/** Suppresses a leading think block while preserving token-by-token final answer delivery. */
final class IncrementalFinalAnswerSanitizer {
    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private final StringBuilder pending = new StringBuilder();
    private boolean answerStarted;
    private boolean thinking;

    String accept(String delta) {
        if (delta == null || delta.isEmpty()) return "";
        if (answerStarted) return delta;
        pending.append(delta);
        String lower = pending.toString().toLowerCase(Locale.ROOT);
        if (!thinking) {
            int first = firstNonWhitespace(pending);
            if (first < 0) return "";
            String candidate = lower.substring(first);
            if (OPEN.startsWith(candidate)) return "";
            if (candidate.startsWith(OPEN)) {
                thinking = true;
            } else {
                answerStarted = true;
                String ready = pending.toString();
                pending.setLength(0);
                return ready;
            }
        }
        int close = lower.indexOf(CLOSE);
        if (close < 0) return "";
        String ready = pending.substring(close + CLOSE.length()).stripLeading();
        pending.setLength(0);
        thinking = false;
        answerStarted = true;
        return ready;
    }

    String finish() {
        if (answerStarted || thinking || pending.isEmpty()) return "";
        String ready = pending.toString();
        pending.setLength(0);
        answerStarted = true;
        return ready;
    }

    private int firstNonWhitespace(CharSequence value) {
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) return index;
        }
        return -1;
    }
}
