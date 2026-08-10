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
})
