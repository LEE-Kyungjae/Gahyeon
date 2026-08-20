package com.gahyeonbot.application.life;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "gahyeon.characters")
@Getter
@Setter
public final class CharacterCatalogProperties {
    private String primaryId = "gahyeon";
    private List<Entry> catalog = new ArrayList<>();

    public static CharacterCatalogProperties standard() {
        var properties = new CharacterCatalogProperties();
        properties.catalog = new ArrayList<>(List.of(
                Entry.of("gahyeon", "가현", "prompts/characters/gahyeon.txt", "gahyeon.assistant", "gahyeon.metahuman", true, 0.65, 12, 0.055, 0.040, 0.030),
                Entry.of("diana", "다이애나", "prompts/characters/diana.txt", "diana.assistant", "diana.metahuman", false, 0.80, 18, 0.040, 0.060, 0.025),
                Entry.of("stella-lily", "스텔라 릴리", "prompts/characters/stella-lily.txt", "gahyeon.assistant", "stella-lily.unreal", false, 0.78, 16, 0.045, 0.055, 0.030),
                Entry.of("ururu", "우루루", "prompts/characters/ururu.txt", "gahyeon.assistant", "ururu.unreal", false, 0.76, 15, 0.050, 0.050, 0.032)));
        return properties;
    }

    @Getter
    @Setter
    public static final class Entry {
        private String id;
        private String displayName;
        private String personaPrompt;
        private String voiceProfile;
        private String expressionProfile;
        private boolean autonomousEnabled;
        private double initiativeThreshold;
        private Duration initiativeCooldown = Duration.ofMinutes(15);
        private double socialDriftPerHour;
        private double curiosityDriftPerHour;
        private double restDriftPerHour;

        static Entry of(String id, String name, String personaPrompt, String voice, String expression,
                        boolean autonomous, double threshold,
                        long cooldownMinutes, double social, double curiosity, double rest) {
            var entry = new Entry();
            entry.id = id;
            entry.displayName = name;
            entry.personaPrompt = personaPrompt;
            entry.voiceProfile = voice;
            entry.expressionProfile = expression;
            entry.autonomousEnabled = autonomous;
            entry.initiativeThreshold = threshold;
            entry.initiativeCooldown = Duration.ofMinutes(cooldownMinutes);
            entry.socialDriftPerHour = social;
            entry.curiosityDriftPerHour = curiosity;
            entry.restDriftPerHour = rest;
            return entry;
        }
    }
}
