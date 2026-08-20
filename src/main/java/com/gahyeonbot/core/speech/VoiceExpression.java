package com.gahyeonbot.core.speech;

import java.util.Locale;
import java.util.Set;

/** Provider-neutral controls for how an utterance should sound. */
public record VoiceExpression(
        String style,
        double intensity,
        String communicativeIntent
) {
    private static final Set<String> STYLES = Set.of(
            "natural", "warm", "gentle", "bright", "surprised", "concerned",
            "serious", "playful", "fake_cute", "sarcastic", "sleepy", "whisper",
            "excited", "annoyed", "sad", "suppressed_laugh");

    public static final VoiceExpression NATURAL = new VoiceExpression("natural", 0.3, "conversation");

    public VoiceExpression {
        style = normalized(style, "style").toLowerCase(Locale.ROOT);
        if (!STYLES.contains(style)) throw new IllegalArgumentException("unsupported voice style: " + style);
        if (!Double.isFinite(intensity) || intensity < 0 || intensity > 1) {
            throw new IllegalArgumentException("voice intensity must be in [0, 1]");
        }
        communicativeIntent = normalized(communicativeIntent, "communicativeIntent");
        if (communicativeIntent.length() > 80) {
            throw new IllegalArgumentException("communicativeIntent is too long");
        }
    }

    private static String normalized(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
