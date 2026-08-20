package com.gahyeonbot.adapters.unreal;

import com.gahyeonbot.application.life.CharacterCognitionResult;
import com.gahyeonbot.core.life.*;
import com.gahyeonbot.core.world.WorldId;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.time.Clock;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.assertThat;

class UnrealAutonomousCognitionPresenterTest {
    @Test
    void speaksOnlyToTheMatchingCharacterAndUsesRendererGeneration() {
        var clients = new UnrealClientSessionRegistry();
        clients.bind("g", new UnrealClientSessionRegistry.Binding(
                "session-g", "home", "ig", "User", "gahyeon"));
        clients.bind("d", new UnrealClientSessionRegistry.Binding(
                "session-d", "home", "id", "User", "diana"));
        var commands = new CapturingCommands();
        commands.generation = 7;
        var speech = new CapturingSpeech();
        var presentationEvents = new ArrayList<String>();
        var outbound = new UnrealEphemeralBroker(Clock.systemUTC());
        outbound.subscribe("g", "session-g", envelope -> presentationEvents.add(envelope.type()));
        var presenter = new UnrealAutonomousCognitionPresenter(clients, commands, speech, outbound);
        var character = new CharacterDefinition(new CharacterId("gahyeon"), "가현", true,
                "persona", "gahyeon.assistant", "gahyeon.default", 0.7,
                Duration.ofMinutes(10), 0.1, 0.1, 0.1);
        var state = CharacterLifeState.initial(new CharacterId("gahyeon"), new WorldId("home"), Instant.EPOCH);
        var expression = new ExpressionPlan("check_in", "concerned", 0.6,
                "concerned", "user", "small_wave", true);

        presenter.present(character, new LifeDecision(LifeDisposition.COGNITION, "test", state, expression),
                new CharacterCognitionResult(true, "우산 가져갔어?", null, 0.4, expression));

        assertThat(speech.prepared).singleElement().satisfies(request -> {
            assertThat(request.sessionId()).isEqualTo("session-g");
            assertThat(request.generation()).isEqualTo(7);
            assertThat(request.voiceProfile().value()).isEqualTo("gahyeon.assistant");
            assertThat(request.expression().style()).isEqualTo("concerned");
        });
        assertThat(speech.ended).hasSize(1);
        assertThat(presentationEvents)
                .containsExactly("emotion.target", "attention.target", "gesture.intent");
    }

    private static final class CapturingCommands implements UnrealCommandDispatcher {
        long generation = -1;
        public DispatchResult dispatch(UnrealConversationCommand command) { return DispatchResult.ACCEPTED; }
        public void advanceGeneration(String sessionId, long value) { generation = value; }
        public long currentGeneration(String sessionId) { return generation; }
        public boolean acceptsAutonomousSpeech(String sessionId) { return true; }
    }

    private static final class CapturingSpeech implements UnrealSpeechPreparationPort {
        final ArrayList<UnrealSpeechPreparationRequest> prepared = new ArrayList<>();
        final ArrayList<UnrealSpeechSequenceEndRequest> ended = new ArrayList<>();
        public void prepare(UnrealSpeechPreparationRequest request, BooleanSupplier current) {
            if (current.getAsBoolean()) prepared.add(request);
        }
        public void finishSequence(UnrealSpeechSequenceEndRequest request, BooleanSupplier current) {
            if (current.getAsBoolean()) ended.add(request);
        }
    }
}
