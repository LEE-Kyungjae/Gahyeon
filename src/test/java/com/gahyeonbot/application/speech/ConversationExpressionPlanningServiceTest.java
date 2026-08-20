package com.gahyeonbot.application.speech;

import com.gahyeonbot.application.life.CharacterCatalogProperties;
import com.gahyeonbot.application.life.CharacterDefinitionRegistry;
import com.gahyeonbot.application.life.CharacterLifeService;
import com.gahyeonbot.application.life.CharacterRelationshipService;
import com.gahyeonbot.core.life.CharacterRelationshipState;
import com.gahyeonbot.core.life.CharacterId;
import com.gahyeonbot.core.life.CharacterLifeState;
import com.gahyeonbot.core.world.WorldId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationExpressionPlanningServiceTest {
    private final CharacterDefinitionRegistry characters =
            new CharacterDefinitionRegistry(CharacterCatalogProperties.standard());
    private final CharacterLifeService life = mock(CharacterLifeService.class);
    private final CharacterRelationshipService relationships = mock(CharacterRelationshipService.class);
    private final ConversationExpressionPlanningService service =
            new ConversationExpressionPlanningService(characters, life, relationships, (ConversationExpressionModel) null);
    private final CharacterId gahyeon = new CharacterId("gahyeon");
    private final WorldId world = new WorldId("gahyeon-home");

    @Test
    void choosesOnlyValidatedPrimaryStylesFromConversationAndLifeState() {
        when(life.current(gahyeon, world)).thenReturn(CharacterLifeState.initial(gahyeon, world, Instant.EPOCH));
        when(relationships.current(gahyeon, world, "42")).thenReturn(
                CharacterRelationshipState.initial(gahyeon, world, "42", Instant.EPOCH));

        assertThat(service.plan(gahyeon, world, "42", "이거 진짜 재밌다ㅋㅋ").style()).isEqualTo("bright");
        assertThat(service.plan(gahyeon, world, "42", "헐, 진짜?").style()).isEqualTo("surprised");
        assertThat(service.plan(gahyeon, world, "42", "오늘 너무 속상해").style()).isEqualTo("sad");
        assertThat(service.plan(gahyeon, world, "42", "그만 약올려").style()).isEqualTo("annoyed");
        assertThat(service.plan(gahyeon, world, "42", "오늘 일정 알려줘").style()).isEqualTo("natural");
    }

    @Test
    void boundsInputAndRestrainsNonPrimaryCharacterIntensity() {
        CharacterId diana = new CharacterId("diana");
        when(life.current(diana, world)).thenReturn(CharacterLifeState.initial(diana, world, Instant.EPOCH));
        when(relationships.current(diana, world, "42")).thenReturn(
                CharacterRelationshipState.initial(diana, world, "42", Instant.EPOCH));

        assertThat(service.plan(diana, world, "42", "좋아ㅋㅋ").intensity()).isLessThan(0.58);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.plan(gahyeon, world, "42", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void highTensionSuppressesBrightMirroring() {
        when(life.current(gahyeon, world)).thenReturn(CharacterLifeState.initial(gahyeon, world, Instant.EPOCH));
        when(relationships.current(gahyeon, world, "42")).thenReturn(new CharacterRelationshipState(
                gahyeon, world, "42", 3, 0.6, 0.4, 0.2, 0.8, Instant.EPOCH, Instant.EPOCH));

        var expression = service.plan(gahyeon, world, "42", "좋아ㅋㅋ");
        assertThat(expression.style()).isEqualTo("natural");
        assertThat(expression.communicativeIntent()).isEqualTo("respond_carefully_under_tension");
    }

    @Test
    void smallModelReceivesOnlyCompactCharacterContextAndCanRefineThePlan() {
        when(life.current(gahyeon, world)).thenReturn(CharacterLifeState.initial(gahyeon, world, Instant.EPOCH));
        when(relationships.current(gahyeon, world, "42")).thenReturn(
                CharacterRelationshipState.initial(gahyeon, world, "42", Instant.EPOCH));
        ConversationExpressionModel model = request -> {
            assertThat(request.characterId()).isEqualTo("gahyeon");
            assertThat(request.expressionProfile()).isEqualTo("gahyeon.metahuman");
            assertThat(request.utterance()).isEqualTo("일부러 귀엽게 말해 볼까?");
            assertThat(request.fallbackStyle()).isEqualTo("natural");
            return Optional.of(new com.gahyeonbot.core.speech.VoiceExpression(
                    "fake_cute", 0.72, "playfully_exaggerate_cuteness"));
        };
        var modeled = new ConversationExpressionPlanningService(characters, life, relationships, model);

        var expression = modeled.plan(gahyeon, world, "42", "일부러 귀엽게 말해 볼까?");

        assertThat(expression.style()).isEqualTo("fake_cute");
        assertThat(expression.communicativeIntent()).isEqualTo("playfully_exaggerate_cuteness");
    }

    @Test
    void modelFailureFallsBackAndTensionPolicyStillOverridesModeledPlayfulness() {
        when(life.current(gahyeon, world)).thenReturn(CharacterLifeState.initial(gahyeon, world, Instant.EPOCH));
        when(relationships.current(gahyeon, world, "42")).thenReturn(new CharacterRelationshipState(
                gahyeon, world, "42", 3, 0.6, 0.4, 0.2, 0.8, Instant.EPOCH, Instant.EPOCH));

        var failed = new ConversationExpressionPlanningService(characters, life, relationships,
                request -> { throw new IllegalStateException("deadline"); });
        assertThat(failed.plan(gahyeon, world, "42", "오늘 일정 알려줘").style()).isEqualTo("natural");

        var unsafe = new ConversationExpressionPlanningService(characters, life, relationships,
                request -> Optional.of(new com.gahyeonbot.core.speech.VoiceExpression(
                        "playful", 0.9, "tease_user")));
        var expression = unsafe.plan(gahyeon, world, "42", "오늘 일정 알려줘");
        assertThat(expression.style()).isEqualTo("natural");
        assertThat(expression.communicativeIntent()).isEqualTo("respond_carefully_under_tension");
    }
}
