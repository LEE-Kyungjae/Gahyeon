package com.gahyeonbot.application.life;

import com.gahyeonbot.application.event.GahyeonEventPublisher;
import com.gahyeonbot.core.event.GahyeonEventDraft;
import com.gahyeonbot.core.life.*;
import com.gahyeonbot.core.world.WorldId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class CharacterLifeService {
    private final CharacterDefinitionRegistry characters;
    private final CharacterLifeStateStore states;
    private final GahyeonEventPublisher events;
    private final DeterministicLifePolicy policy;
    private final Clock clock;
    private ApplicationEventPublisher internalEvents = event -> {};

    @Autowired
    public CharacterLifeService(CharacterDefinitionRegistry characters, CharacterLifeStateStore states, GahyeonEventPublisher events) {
        this(characters, states, events, new DeterministicLifePolicy(), Clock.systemUTC());
    }

    CharacterLifeService(CharacterDefinitionRegistry characters, CharacterLifeStateStore states,
                         GahyeonEventPublisher events, DeterministicLifePolicy policy, Clock clock) {
        this.characters = characters;
        this.states = states;
        this.events = events;
        this.policy = policy;
        this.clock = clock;
    }

    @Autowired
    void setInternalEvents(ApplicationEventPublisher internalEvents) {
        this.internalEvents = internalEvents;
    }

    @Transactional
    public CharacterLifeState current(CharacterId characterId, WorldId worldId) {
        characters.require(characterId);
        return states.find(characterId, worldId)
                .orElseGet(() -> states.save(CharacterLifeState.initial(characterId, worldId, clock.instant())));
    }

    @Transactional
    public LifeDecision observe(CharacterId characterId, WorldId worldId, LifeStimulus stimulus) {
        CharacterDefinition definition = characters.require(characterId);
        CharacterLifeState before = current(characterId, worldId);
        LifeDecision decision = policy.decide(definition, before, stimulus);
        CharacterLifeState saved = states.save(decision.nextState());
        LifeDecision durable = new LifeDecision(decision.disposition(), decision.reason(), saved, decision.expressionPlan());
        publish(durable, stimulus);
        if (durable.disposition() == LifeDisposition.COGNITION) {
            internalEvents.publishEvent(new CharacterCognitionRequested(durable));
        }
        return durable;
    }

    @Transactional
    public LifeDecision tick(CharacterId characterId, WorldId worldId) {
        return observe(characterId, worldId, LifeStimulus.idleTick(clock.instant()));
    }

    private void publish(LifeDecision decision, LifeStimulus stimulus) {
        CharacterLifeState state = decision.nextState();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("characterId", state.characterId().value());
        payload.put("worldId", state.worldId().value());
        payload.put("revision", state.revision());
        payload.put("disposition", decision.disposition().name().toLowerCase());
        payload.put("reason", decision.reason());
        payload.put("activity", state.activity());
        payload.put("needs", Map.of("social", state.socialNeed(), "curiosity", state.curiosityNeed(), "rest", state.restNeed()));
        payload.put("stimulusType", stimulus.type());
        if (decision.expressionPlan() != null) payload.put("expressionPlan", decision.expressionPlan());
        events.publish(GahyeonEventDraft.world(
                "character.life.decided", state.worldId().value(),
                "life:" + state.characterId().value() + ":" + state.revision() + ":" + UUID.randomUUID(),
                Map.copyOf(payload)));
    }
}
