package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.life.CharacterCognitionPresentationPort;
import com.gahyeonbot.application.life.CharacterCognitionResult;
import com.gahyeonbot.core.life.CharacterDefinition;
import com.gahyeonbot.core.life.LifeDecision;
import com.gahyeonbot.core.speech.VoiceExpression;
import com.gahyeonbot.core.speech.VoiceProfileId;

/** Routes autonomous speech only to Unreal sessions rendering the matching persona. */
public final class UnrealAutonomousCognitionPresenter implements CharacterCognitionPresentationPort {
    private final UnrealClientSessionRegistry clients;
    private final UnrealCommandDispatcher commands;
    private final UnrealSpeechPreparationPort speech;
    private final UnrealEphemeralBroker outbound;

    public UnrealAutonomousCognitionPresenter(UnrealClientSessionRegistry clients,
            UnrealCommandDispatcher commands, UnrealSpeechPreparationPort speech) {
        this(clients, commands, speech, null);
    }

    public UnrealAutonomousCognitionPresenter(UnrealClientSessionRegistry clients,
            UnrealCommandDispatcher commands, UnrealSpeechPreparationPort speech,
            UnrealEphemeralBroker outbound) {
        this.clients = clients;
        this.commands = commands;
        this.speech = speech;
        this.outbound = outbound;
    }

    @Override
    public void present(CharacterDefinition character, LifeDecision decision,
            CharacterCognitionResult result) {
        if (!result.speak()) return;
        String characterId = character.id().value();
        String worldId = decision.nextState().worldId().value();
        var expressionPlan = result.expressionPlan();
        var expression = new VoiceExpression(expressionPlan.voiceStyle(), expressionPlan.intensity(),
                expressionPlan.communicativeIntent());
        for (var binding : clients.sessionsFor(worldId, characterId)) {
            String sessionId = binding.sessionId();
            if (!commands.acceptsAutonomousSpeech(sessionId)) continue;
            long observed = commands.currentGeneration(sessionId);
            long generation = observed < 0 ? 0 : observed;
            if (observed < 0) commands.advanceGeneration(sessionId, generation);
            String correlation = "life:" + characterId + ":" + decision.nextState().revision();
            java.util.function.BooleanSupplier current =
                    () -> commands.currentGeneration(sessionId) == generation;
            publishPresentation(sessionId, correlation, generation, expressionPlan);
            speech.prepare(new UnrealSpeechPreparationRequest(
                    sessionId, correlation, generation, 0, result.utterance(),
                    new VoiceProfileId(character.voiceProfile()), expression), current);
            speech.finishSequence(new UnrealSpeechSequenceEndRequest(
                    sessionId, correlation, generation, 1, "completed"), current);
        }
    }

    private void publishPresentation(String sessionId, String correlation, long generation,
            com.gahyeonbot.core.life.ExpressionPlan expression) {
        if (outbound == null) return;
        outbound.publish(sessionId, "emotion.target", correlation, java.util.Map.of(
                "generation", generation,
                "dimensions", java.util.Map.of(expression.facialExpression(), expression.intensity()),
                "blendSeconds", 0.25,
                "holdSeconds", 4.0));
        outbound.publish(sessionId, "attention.target", correlation, java.util.Map.of(
                "generation", generation,
                "kind", expression.gazeTarget(),
                "priority", 50,
                "expiresAfterMs", 4_000));
        if (!"none".equals(expression.gesture())) {
            outbound.publish(sessionId, "gesture.intent", correlation, java.util.Map.of(
                    "generation", generation,
                    "semantic", expression.gesture(),
                    "intensity", expression.intensity(),
                    "priority", 50,
                    "expiresAfterMs", 4_000));
        }
    }
}
