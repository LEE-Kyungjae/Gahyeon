import { describe, expect, it } from 'vitest'
import { isEventVisibleToWorld } from './world-event-admission'

describe('Desktop world event admission', () => {
  it('admits a world event only for its exact envelope scope', () => {
    const event = {
      event: 'world.transition.target',
      id: '19',
      data: {
        scope: { type: 'WORLD', id: 'gahyeon-home' },
        payload: { worldId: 'gahyeon-home', actionId: 'action-19' },
      },
    }
    expect(isEventVisibleToWorld(event, 'gahyeon-home')).toBe(true)
    expect(isEventVisibleToWorld(event, 'another-world')).toBe(false)
  })

  it('fails closed for a missing, malformed, or conflicting world scope', () => {
    expect(isEventVisibleToWorld({
      event: 'character.moved', data: { payload: { revision: 2 } },
    }, 'gahyeon-home')).toBe(false)
    expect(isEventVisibleToWorld({
      event: 'world.state.changed',
      data: {
        scope: { type: 'SESSION', id: 'gahyeon-home' },
        payload: { worldId: 'gahyeon-home' },
      },
    }, 'gahyeon-home')).toBe(false)
    expect(isEventVisibleToWorld({
      event: 'world.state.changed',
      data: {
        scope: { type: 'WORLD', id: 'gahyeon-home' },
        payload: { worldId: 'another-world' },
      },
    }, 'gahyeon-home')).toBe(false)
  })

  it('does not require world scope on session and ephemeral presentation events', () => {
    expect(isEventVisibleToWorld({
      event: 'conversation.delta', data: { requestId: 'r1', delta: '안녕' },
    }, 'gahyeon-home')).toBe(true)
    expect(isEventVisibleToWorld({
      event: 'stream.connected', data: { sequence: 4 },
    }, 'gahyeon-home')).toBe(true)
    expect(isEventVisibleToWorld({
      event: 'avatar.speech.started', data: {},
    }, 'gahyeon-home')).toBe(true)
  })

  it('rejects any foreign WORLD envelope even for a future event type', () => {
    expect(isEventVisibleToWorld({
      event: 'future.world.effect',
      data: { scope: { type: 'WORLD', id: 'another-world' }, payload: {} },
    }, 'gahyeon-home')).toBe(false)
  })
})
