package com.gahyeonbot.application.speech;

import com.gahyeonbot.application.life.CharacterDefinitionRegistry;
import com.gahyeonbot.application.life.CharacterLifeService;
import com.gahyeonbot.application.life.CharacterRelationshipService;
import com.gahyeonbot.core.life.CharacterId;
import com.gahyeonbot.core.life.CharacterLifeState;
import com.gahyeonbot.core.speech.VoiceExpression;
import com.gahyeonbot.core.world.WorldId;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fast, Core-owned expression prior used before the first streamed sentence is spoken. */
@Service
public final class ConversationExpressionPlanningService {
    private static final Logger log = LoggerFactory.getLogger(ConversationExpressionPlanningService.class);
    private static final int MAXIMUM_MESSAGE_CHARACTERS = 16_384;

    private final CharacterDefinitionRegistry characters;
    private final CharacterLifeService life;
    private final CharacterRelationshipService relationships;
    private final ConversationExpressionModel model;

    @Autowired
    public ConversationExpressionPlanningService(
            CharacterDefinitionRegistry characters,
            CharacterLifeService life,
            CharacterRelationshipService relationships,
            ObjectProvider<ConversationExpressionModel> model) {
        this(characters, life, relationships, model.getIfAvailable());
    }

    ConversationExpressionPlanningService(
            CharacterDefinitionRegistry characters,
            CharacterLifeService life,
            CharacterRelationshipService relationships,
            ConversationExpressionModel model) {
        this.characters = characters;
        this.life = life;
        this.relationships = relationships;
        this.model = model;
    }

    public VoiceExpression plan(CharacterId characterId, WorldId worldId, String subjectId, String message) {
        if (message == null || message.isBlank() || message.length() > MAXIMUM_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException("conversation message is invalid");
        }
        var character = characters.require(characterId);
        CharacterLifeState state = life.current(characterId, worldId);
        var relationship = relationships.current(characterId, worldId, subjectId);
        String normalized = message.trim().toLowerCase(Locale.ROOT);

        String style = "natural";
        String intent = "conversation";
        double intensity = 0.30;
        if (containsAny(normalized, "ㅋㅋ", "ㅎㅎ", "재밌", "웃겨", "좋아", "고마워")) {
            style = "bright";
            intent = "share_positive_affect";
            intensity = 0.58;
        } else if (containsAny(normalized, "헐", "대박", "진짜?", "정말?", "놀랐")) {
            style = "surprised";
            intent = "react_to_surprise";
            intensity = 0.64;
        } else if (containsAny(normalized, "슬퍼", "속상", "힘들", "울고", "외로")) {
            style = "sad";
            intent = "acknowledge_distress";
            intensity = 0.48;
        } else if (containsAny(normalized, "약올", "놀리지 마", "놀리지마", "바보야")) {
            style = "annoyed";
            intent = "playful_objection";
            intensity = 0.44;
        } else if (state.valence() >= 0.45) {
            style = "bright";
            intent = "continue_positive_mood";
            intensity = 0.42 + state.arousal() * 0.20;
        } else if (state.valence() <= -0.35) {
            style = "sad";
            intent = "speak_from_low_mood";
            intensity = 0.35 + Math.abs(state.valence()) * 0.25;
        }

        VoiceExpression fallback = new VoiceExpression(style, intensity, intent);
        VoiceExpression selected = modelPlan(character.id().value(), character.expressionProfile(), character.primary(),
                message.trim(), state.activity(), state.valence(), state.arousal(),
                relationship.familiarity(), relationship.trust(), relationship.affinity(), relationship.tension(), fallback)
                .orElse(fallback);

        style = selected.style();
        intent = selected.communicativeIntent();
        intensity = selected.intensity();
        double relationshipScale = 0.72
                + relationship.familiarity() * 0.12
                + relationship.trust() * 0.10
                + relationship.affinity() * 0.10
                - relationship.tension() * 0.16;
        intensity *= Math.max(0.55, Math.min(1.04, relationshipScale));
        if (relationship.tension() >= 0.55 && isUnsafeUnderTension(style)) {
            style = "natural";
            intent = "respond_carefully_under_tension";
            intensity = 0.28;
        }
        // Each personality owns an expression profile. Non-primary profiles start
        // slightly restrained until their own listening calibration is approved.
        if (!character.primary()) intensity *= 0.85;
        return new VoiceExpression(style, Math.max(0, Math.min(1, intensity)), intent);
    }

    private Optional<VoiceExpression> modelPlan(
            String characterId, String expressionProfile, boolean primary, String utterance,
            String activity, double valence, double arousal, double familiarity, double trust,
            double affinity, double tension, VoiceExpression fallback) {
        if (model == null) return Optional.empty();
        try {
            return model.plan(new ConversationExpressionModelRequest(
                    characterId, expressionProfile, primary, utterance, activity, valence, arousal,
                    familiarity, trust, affinity, tension, fallback.style(), fallback.intensity(),
                    fallback.communicativeIntent()));
        } catch (RuntimeException failure) {
            log.debug("Small expression model failed; using deterministic plan for character {}", characterId, failure);
            return Optional.empty();
        }
    }

    private static boolean isUnsafeUnderTension(String style) {
        return "bright".equals(style) || "playful".equals(style) || "fake_cute".equals(style)
                || "sarcastic".equals(style) || "suppressed_laugh".equals(style);
    }

    private static boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) if (text.contains(candidate)) return true;
        return false;
    }
}
