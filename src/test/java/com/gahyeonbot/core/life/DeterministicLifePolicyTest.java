package com.gahyeonbot.core.life;

import com.gahyeonbot.core.world.WorldId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicLifePolicyTest {
    private final DeterministicLifePolicy policy = new DeterministicLifePolicy();
    private final CharacterDefinition gahyeon = definition("gahyeon", 0.72, Duration.ofMinutes(12));

    @Test
    void elapsedTimeChangesNeedsWithoutForcingSpeech() {
        Instant start = Instant.parse("2026-08-19T00:00:00Z");
        var current = CharacterLifeState.initial(gahyeon.id(), new WorldId("home"), start);

        LifeDecision decision = policy.decide(gahyeon, current, LifeStimulus.idleTick(start.plus(Duration.ofHours(2))));

        assertThat(decision.disposition()).isEqualTo(LifeDisposition.INTERNAL_CHANGE);
        assertThat(decision.nextState().socialNeed()).isGreaterThan(current.socialNeed());
        assertThat(decision.expressionPlan()).isNull();
    }

    @Test
    void accumulatedInternalNeedsEventuallyInviteAQuietReflection() {
        Instant start = Instant.parse("2026-08-19T00:00:00Z");
        var current = CharacterLifeState.initial(gahyeon.id(), new WorldId("home"), start);

        LifeDecision decision = policy.decide(gahyeon, current,
                LifeStimulus.idleTick(start.plus(Duration.ofHours(14))));

        assertThat(decision.disposition()).isEqualTo(LifeDisposition.COGNITION);
        assertThat(decision.reason()).isEqualTo("internal_needs_invite_reflection");
        assertThat(decision.expressionPlan().communicativeIntent()).isEqualTo("self_directed_reflection");
        assertThat(decision.expressionPlan().gazeTarget()).isEqualTo("ambient");
        assertThat(decision.nextState().lastInitiativeAt()).isEqualTo(start.plus(Duration.ofHours(14)));
        assertThat(decision.nextState().socialNeed()).isLessThan(0.9);
    }

    @Test
    void internalReflectionRespectsInitiativeCooldown() {
        Instant now = Instant.parse("2026-08-19T14:00:00Z");
        var current = new CharacterLifeState(gahyeon.id(), new WorldId("home"), 8, "reading",
                0.1, 0.2, 0.95, 0.90, 0.2, "book", "finish_chapter", null,
                null, now.minus(Duration.ofMinutes(5)), now.minus(Duration.ofHours(1)));

        LifeDecision decision = policy.decide(gahyeon, current, LifeStimulus.idleTick(now));

        assertThat(decision.disposition()).isEqualTo(LifeDisposition.INTERNAL_CHANGE);
        assertThat(decision.expressionPlan()).isNull();
        assertThat(decision.nextState().lastInitiativeAt()).isEqualTo(current.lastInitiativeAt());
    }

    @Test
    void salientExpiringEventRequestsCognitionWithSharedExpressionPlan() {
        Instant now = Instant.parse("2026-08-19T10:00:00Z");
        var current = new CharacterLifeState(gahyeon.id(), new WorldId("home"), 4, "reading",
                0.2, 0.3, 0.8, 0.7, 0.1, "book", "finish_chapter", null, now.minusSeconds(600), null, now.minusSeconds(600));

        LifeDecision decision = policy.decide(gahyeon, current,
                new LifeStimulus("surprising.event", 0.95, "window", true, now));

        assertThat(decision.disposition()).isEqualTo(LifeDisposition.COGNITION);
        assertThat(decision.expressionPlan().voiceStyle()).isEqualTo("surprised");
        assertThat(decision.expressionPlan().resumePreviousActivity()).isTrue();
    }

    @Test
    void initiativeCooldownTurnsSpeechIntoNonverbalAction() {
        Instant now = Instant.parse("2026-08-19T10:00:00Z");
        var current = new CharacterLifeState(gahyeon.id(), new WorldId("home"), 4, "reading",
                0, 0.2, 0.9, 0.8, 0.1, null, "read", null, null, now.minusSeconds(30), now.minusSeconds(30));

        LifeDecision decision = policy.decide(gahyeon, current,
                new LifeStimulus("playful.event", 1, "user", true, now));

        assertThat(decision.disposition()).isEqualTo(LifeDisposition.ACTION);
        assertThat(decision.reason()).isEqualTo("initiative_cooldown_action_only");
        assertThat(decision.expressionPlan()).isNull();
    }

    @Test
    void characterDefinitionCannotDriveAnotherCharactersState() {
        Instant now = Instant.parse("2026-08-19T10:00:00Z");
        var diana = CharacterLifeState.initial(new CharacterId("diana"), new WorldId("home"), now);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> policy.decide(gahyeon, diana, LifeStimulus.idleTick(now)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match");
    }

    @Test
    void prospectiveIntentionSurvivesUntilUserReturnsThenRequestsCognition() {
        Instant now = Instant.parse("2026-08-19T10:00:00Z");
        var initial = CharacterLifeState.initial(gahyeon.id(), new WorldId("home"), now);
        LifeDecision remembered = policy.decide(gahyeon, initial,
                new LifeStimulus("prospective.intention", 0.5, "ask_about_umbrella", false, now));

        LifeDecision returned = policy.decide(gahyeon, remembered.nextState(),
                new LifeStimulus("user.returned", 0.7, "user", true, now.plusSeconds(60)));

        assertThat(remembered.disposition()).isEqualTo(LifeDisposition.INTERNAL_CHANGE);
        assertThat(remembered.nextState().prospectiveIntention()).isEqualTo("ask_about_umbrella");
        assertThat(returned.disposition()).isEqualTo(LifeDisposition.COGNITION);
        assertThat(returned.reason()).isEqualTo("prospective_intention_due");
        assertThat(returned.nextState().prospectiveIntention()).isNull();
    }

    private CharacterDefinition definition(String id, double threshold, Duration cooldown) {
        return new CharacterDefinition(new CharacterId(id), id, "gahyeon".equals(id), "prompts/characters/" + id + ".txt", id + ".voice", id + ".expression",
                threshold, cooldown, 0.05, 0.04, 0.03);
    }
}
