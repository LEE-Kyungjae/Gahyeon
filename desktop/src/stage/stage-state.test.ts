import { describe, expect, it } from 'vitest'
import { initialStageState, reduceStageEvent } from './stage-state'

describe('stage state reducer', () => {
  it('reduces semantic movement and expression events', () => {
    const moved = reduceStageEvent(initialStageState, {
      event: 'character.moved',
      id: '11',
      data: { payload: { room: 'workspace', position: { x: 3, y: 0, z: -2 } } },
    })
    const expressed = reduceStageEvent(moved, {
      event: 'avatar.expression',
      id: '12',
      data: { payload: { expression: 'happy', intensity: 1.4 } },
    })

    expect(expressed).toMatchObject({
      room: 'workspace',
      position: { x: 3, y: 0, z: -2 },
      expression: 'happy',
      expressionIntensity: 1,
    })
  })

  it('maps conversation lifecycle to deterministic attention state', () => {
    const attentive = reduceStageEvent(initialStageState, {
      event: 'conversation.started',
      data: {},
    })
    expect(attentive.activity).toBe('attention')
    expect(reduceStageEvent(attentive, {
      event: 'conversation.completed',
      data: {},
    }).activity).toBe('idle')
    expect(reduceStageEvent(attentive, {
      event: 'conversation.cancelled', data: {},
    }).activity).toBe('idle')
  })

  it('reacts to microphone lifecycle without waiting for cognition', () => {
    const listening = reduceStageEvent(initialStageState, {
      event: 'perception.voice.started', data: {},
    })
    const thinking = reduceStageEvent(listening, {
      event: 'perception.voice.ended', data: {},
    })
    const cancelled = reduceStageEvent(thinking, {
      event: 'perception.voice.cancelled', data: {},
    })
    expect(listening.activity).toBe('listening')
    expect(thinking.activity).toBe('thinking')
    expect(cancelled.activity).toBe('idle')
  })

  it('maps actual audio playback to the conversation animation layer', () => {
    const speaking = reduceStageEvent(initialStageState, {
      event: 'avatar.speech.started', data: {},
    })
    const stopped = reduceStageEvent(speaking, {
      event: 'avatar.speech.stopped', data: {},
    })
    expect(speaking).toMatchObject({ activity: 'conversation', speaking: true })
    expect(stopped).toMatchObject({ activity: 'idle', speaking: false })
    expect(reduceStageEvent(speaking, {
      event: 'conversation.completed', data: {},
    }).activity).toBe('conversation')
  })

  it('closes the mouth when a conversation fails or is cancelled mid-speech', () => {
    const speaking = {
      ...initialStageState,
      activity: 'conversation',
      speaking: true,
      speechAmplitude: 0.8,
    }
    for (const event of ['conversation.failed', 'conversation.cancelled']) {
      expect(reduceStageEvent(speaking, { event, data: {} })).toMatchObject({
        activity: 'idle',
        speaking: false,
        speechAmplitude: 0,
      })
    }
  })

  it('restores persisted snapshots and ignores older world revisions', () => {
    const restored = reduceStageEvent(initialStageState, {
      event: 'world.state.restored',
      data: {
        revision: 8,
        currentRoom: 'living_room',
        position: { x: -2, y: 0, z: -5 },
        activity: 'RELAX',
        emotion: 'happy',
      },
    })
    const stale = reduceStageEvent(restored, {
      event: 'character.moved',
      data: { payload: { revision: 7, room: 'bedroom', position: { x: 0, y: 0, z: 0 } } },
    })

    expect(restored).toMatchObject({
      revision: 8,
      room: 'living_room',
      activity: 'relax',
      expression: 'happy',
    })
    expect(stale).toBe(restored)
  })

  it('starts a renderer action only from its exact authoritative revision', () => {
    const current = { ...initialStageState, revision: 7 }
    const admitted = reduceStageEvent(current, {
      event: 'world.transition.target',
      data: { payload: {
        actionId: 'action-18',
        expectedRevision: 7,
        room: 'workspace',
        position: { x: 7.5, y: 0, z: -2.4 },
        activity: 'WORK',
        interactionTarget: 'desk',
      } },
    })
    const fromWrongRevision = reduceStageEvent(current, {
      event: 'world.transition.target',
      data: { payload: {
        actionId: 'stale-action',
        expectedRevision: 6,
        room: 'living_room',
        position: { x: 0, y: 0, z: -7 },
        activity: 'relax',
      } },
    })

    expect(admitted.pendingWorldAction).toEqual({
      actionId: 'action-18',
      expectedRevision: 7,
      room: 'workspace',
      position: { x: 7.5, y: 0, z: -2.4 },
      activity: 'work',
      interactionTarget: 'desk',
    })
    expect(fromWrongRevision).toBe(current)
  })

  it('defers a target that races ahead of initial snapshot hydration', () => {
    const ahead = reduceStageEvent(initialStageState, {
      event: 'world.transition.target',
      data: { payload: {
        actionId: 'action-8', expectedRevision: 8, room: 'workspace',
        position: { x: 7, y: 0, z: -2 }, activity: 'work',
      } },
    })
    expect(ahead.pendingWorldAction).toBeUndefined()
    expect(ahead.deferredWorldAction?.actionId).toBe('action-8')

    const hydrated = reduceStageEvent(ahead, {
      event: 'world.state.restored',
      data: {
        revision: 8, currentRoom: 'bedroom', position: { x: 0, y: 0, z: 0 },
        activity: 'idle', emotion: 'neutral',
      },
    })
    expect(hydrated.deferredWorldAction).toBeUndefined()
    expect(hydrated.pendingWorldAction?.actionId).toBe('action-8')
  })

  it('clears only the matching action result and committed revision', () => {
    const pending = reduceStageEvent({ ...initialStageState, revision: 3 }, {
      event: 'world.transition.target',
      data: { payload: {
        actionId: 'action-3', expectedRevision: 3, room: 'workspace',
        position: { x: 7, y: 0, z: -2 }, activity: 'work',
      } },
    })
    expect(reduceStageEvent(pending, {
      event: 'character.action.result', data: { payload: { actionId: 'other' } },
    }).pendingWorldAction).toBeDefined()
    expect(reduceStageEvent(pending, {
      event: 'character.action.result',
      data: { payload: { actionId: 'action-3', result: 'stale' } },
    }).pendingWorldAction).toBeDefined()
    expect(reduceStageEvent(pending, {
      event: 'character.action.result',
      data: { payload: { actionId: 'action-3', result: 'recorded_failure' } },
    }).pendingWorldAction).toBeUndefined()
    expect(reduceStageEvent(pending, {
      event: 'character.moved',
      data: { payload: {
        revision: 4, room: 'workspace', position: { x: 7, y: 0, z: -2 },
      } },
    }).pendingWorldAction).toBeUndefined()
  })

  it('reads the nested emotion shape emitted by durable world snapshots', () => {
    const changed = reduceStageEvent(initialStageState, {
      event: 'world.state.changed',
      data: { payload: {
        revision: 1,
        currentRoom: 'bedroom',
        position: { x: 0, y: 0, z: 0 },
        activity: 'idle',
        emotion: { name: 'curious', intensity: 0.65 },
      } },
    })
    expect(changed).toMatchObject({ expression: 'curious', expressionIntensity: 0.65 })
  })
})
