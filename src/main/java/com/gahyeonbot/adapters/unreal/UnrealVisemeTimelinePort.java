package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.core.speech.AudioOutput;

import java.util.List;

/** Optional TTS-provider-independent alignment boundary. Empty means amplitude fallback. */
@FunctionalInterface
public interface UnrealVisemeTimelinePort {
    List<UnrealVisemeCue> align(String text, AudioOutput audio);

    default String source() {
        return "provider";
    }

    static UnrealVisemeTimelinePort unavailable() {
        return (text, audio) -> List.of();
    }
}
