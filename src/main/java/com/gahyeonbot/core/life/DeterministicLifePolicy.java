package com.gahyeonbot.core.life;

import java.time.Duration;
import java.time.Instant;

public final class DeterministicLifePolicy {
    public LifeDecision decide(CharacterDefinition character, CharacterLifeState current, LifeStimulus stimulus) {
        if (!character.id().equals(current.characterId())) throw new IllegalArgumentException("character definition and state do not match");
        Instant now = stimulus.observedAt().isBefore(current.updatedAt()) ? current.updatedAt() : stimulus.observedAt();
        double hours = Math.min(24, Math.max(0, Duration.between(current.updatedAt(), now).toMillis() / 3_600_000d));
        double social = unit(current.socialNeed() + character.socialDriftPerHour() * hours);
        double curiosity = unit(current.curiosityNeed() + character.curiosityDriftPerHour() * hours);
        double rest = unit(current.restNeed() + character.restDriftPerHour() * hours);
        double salience = unit(stimulus.importance() * 0.58 + social * 0.22 + curiosity * 0.14 + current.arousal() * 0.06);
        double internalDrive = unit(social * 0.58 + curiosity * 0.32 + current.arousal() * 0.10);
        boolean elapsedTime = "time.elapsed".equals(stimulus.type());
        boolean cooldownActive = current.lastInitiativeAt() != null
                && Duration.between(current.lastInitiativeAt(), now).compareTo(character.initiativeCooldown()) < 0;

        LifeDisposition disposition;
        String reason;
        ExpressionPlan expression = null;
        Instant initiativeAt = current.lastInitiativeAt();
        String attention = current.attentionTarget();
        String intention = current.prospectiveIntention();

        if ("prospective.intention".equals(stimulus.type()) && stimulus.subject() != null) {
            disposition = LifeDisposition.INTERNAL_CHANGE;
            reason = "prospective_intention_recorded";
            intention = stimulus.subject();
        } else if ("user.returned".equals(stimulus.type()) && intention != null && !cooldownActive) {
            disposition = LifeDisposition.COGNITION;
            reason = "prospective_intention_due";
            expression = plan("resume_intention", "warm", Math.max(0.35, salience), "attentive", "user", "small_head_turn");
            initiativeAt = now;
            attention = "user";
            intention = null;
            social = unit(social - 0.18);
        } else if (elapsedTime && hours > 0 && internalDrive >= character.initiativeThreshold() && !cooldownActive) {
            disposition = LifeDisposition.COGNITION;
            reason = "internal_needs_invite_reflection";
            String style = social >= curiosity ? "gentle" : "natural";
            expression = plan("self_directed_reflection", style, internalDrive,
                    "thoughtful", "ambient", "settle_and_breathe");
            initiativeAt = now;
            if (social >= curiosity) social = unit(social - 0.18);
            else curiosity = unit(curiosity - 0.18);
        } else if (salience >= character.initiativeThreshold() && !cooldownActive && stimulus.expiresIfIgnored()) {
            disposition = LifeDisposition.COGNITION;
            reason = "salient_time_sensitive_event";
            expression = plan("contextual_reaction", styleFor(stimulus.type()), salience, "attentive", stimulus.subject(), "orient");
            initiativeAt = now;
            attention = stimulus.subject();
            curiosity = unit(curiosity - 0.12);
        } else if (stimulus.importance() >= 0.45) {
            disposition = LifeDisposition.ACTION;
            reason = cooldownActive ? "initiative_cooldown_action_only" : "event_warrants_nonverbal_action";
            attention = stimulus.subject();
            curiosity = unit(curiosity - 0.06);
        } else if (hours > 0 || stimulus.importance() > 0) {
            disposition = LifeDisposition.INTERNAL_CHANGE;
            reason = "state_advanced_without_interruption";
        } else {
            disposition = LifeDisposition.SILENCE;
            reason = "no_meaningful_change";
        }

        CharacterLifeState next = new CharacterLifeState(
                current.characterId(), current.worldId(), current.revision() + 1,
                activityFor(rest, current.activity()), current.valence(), arousalFor(stimulus, current.arousal()),
                social, curiosity, rest, attention, current.currentGoal(), intention,
                "user.interaction".equals(stimulus.type()) ? now : current.lastInteractionAt(), initiativeAt, now);
        return new LifeDecision(disposition, reason, next, expression);
    }

    private ExpressionPlan plan(String intent, String voiceStyle, double intensity, String face, String gaze, String gesture) {
        return new ExpressionPlan(intent, voiceStyle, unit(intensity), face, gaze, gesture, true);
    }

    private String activityFor(double restNeed, String current) {
        return restNeed >= 0.86 ? "resting" : current;
    }

    private double arousalFor(LifeStimulus stimulus, double current) {
        return unit(current * 0.88 + stimulus.importance() * 0.22);
    }

    private String styleFor(String stimulusType) {
        return switch (stimulusType) {
            case "user.returned" -> "warm";
            case "surprising.event" -> "surprised";
            case "playful.event" -> "playful";
            default -> "natural";
        };
    }

    private double unit(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
