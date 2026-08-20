package com.gahyeonbot.application.life;

import com.gahyeonbot.application.event.GahyeonEventPublisher;
import com.gahyeonbot.core.event.GahyeonEventDraft;
import com.gahyeonbot.core.life.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public final class CharacterCognitionService {
    private final CharacterDefinitionRegistry characters;
    private final CharacterMemoryStore memories;
    private final ObjectProvider<CharacterCognitionPort> portProvider;
    private final GahyeonEventPublisher events;
    private final List<CharacterCognitionPresentationPort> presentations;
    private final Clock clock;

    @Autowired
    public CharacterCognitionService(CharacterDefinitionRegistry characters, CharacterMemoryStore memories,
            ObjectProvider<CharacterCognitionPort> portProvider, GahyeonEventPublisher events,
            List<CharacterCognitionPresentationPort> presentations) {
        this(characters, memories, portProvider, events, presentations, Clock.systemUTC());
    }

    CharacterCognitionService(CharacterDefinitionRegistry characters, CharacterMemoryStore memories,
            ObjectProvider<CharacterCognitionPort> portProvider, GahyeonEventPublisher events, Clock clock) {
        this(characters, memories, portProvider, events, List.of(), clock);
    }

    CharacterCognitionService(CharacterDefinitionRegistry characters, CharacterMemoryStore memories,
            ObjectProvider<CharacterCognitionPort> portProvider, GahyeonEventPublisher events,
            List<CharacterCognitionPresentationPort> presentations, Clock clock) {
        this.characters = characters;
        this.memories = memories;
        this.portProvider = portProvider;
        this.events = events;
        this.presentations = List.copyOf(presentations);
        this.clock = clock;
    }

    public boolean process(LifeDecision decision) {
        if (decision.disposition() != LifeDisposition.COGNITION) return false;
        CharacterCognitionPort port = portProvider.getIfAvailable();
        if (port == null || !port.isReady()) {
            publishStatus("character.cognition.skipped", decision, Map.of("cause", "runtime_not_ready"));
            return false;
        }
        CharacterLifeState state = decision.nextState();
        CharacterDefinition character = characters.require(state.characterId());
        var request = new CharacterCognitionRequest(character, state, decision.reason(),
                decision.expressionPlan(), memories.recent(state.characterId(), state.worldId(), 12));
        try {
            CharacterCognitionResult result = port.generate(request);
            if (result.memoryNote() != null) memories.append(new CharacterMemory(0, state.characterId(), state.worldId(),
                    "reflection", result.memoryNote(), result.memoryImportance(), clock.instant()));
            if (result.speak()) memories.append(new CharacterMemory(0, state.characterId(), state.worldId(),
                    "utterance", result.utterance(), Math.max(0.35, result.memoryImportance()), clock.instant()));
            var payload = new LinkedHashMap<String, Object>();
            payload.put("spoken", result.speak());
            if (result.speak()) payload.put("utterance", result.utterance());
            payload.put("expressionPlan", result.expressionPlan());
            payload.put("voiceProfile", character.voiceProfile());
            payload.put("expressionProfile", character.expressionProfile());
            publishStatus("character.cognition.completed", decision, payload);
            if (result.speak()) {
                for (CharacterCognitionPresentationPort presentation : presentations) {
                    try {
                        presentation.present(character, decision, result);
                    } catch (RuntimeException ignored) {
                        // Durable cognition state must survive an optional renderer failure.
                    }
                }
            }
            return true;
        } catch (RuntimeException failure) {
            publishStatus("character.cognition.failed", decision,
                    Map.of("cause", failure.getClass().getSimpleName()));
            return false;
        }
    }

    private void publishStatus(String type, LifeDecision decision, Map<String, Object> detail) {
        CharacterLifeState state = decision.nextState();
        var payload = new LinkedHashMap<String, Object>(detail);
        payload.put("characterId", state.characterId().value());
        payload.put("worldId", state.worldId().value());
        payload.put("revision", state.revision());
        payload.put("reason", decision.reason());
        events.publish(GahyeonEventDraft.world(type, state.worldId().value(),
                "cognition:" + state.characterId().value() + ":" + state.revision() + ":" + UUID.randomUUID(),
                Map.copyOf(payload)));
    }
}
