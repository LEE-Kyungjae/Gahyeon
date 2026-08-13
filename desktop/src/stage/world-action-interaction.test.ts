import { describe, expect, it } from 'vitest'
import type { PendingWorldAction } from './stage-state'
import { WorldActionInteractionGate } from './world-action-interaction'

describe('WorldActionInteractionGate', () => {
  const work: PendingWorldAction = {
    actionId: 'work-1', expectedRevision: 7, room: 'workspace',
    position: { x: 7, y: 0, z: -2 }, activity: 'work', interactionTarget: 'desk',
  }

  it('does not complete sit/read/work until interaction has visibly settled', () => {
    const gate = new WorldActionInteractionGate()
    expect(gate.advance(work, false, 10, false)).toBe(false)
    expect(gate.advance(work, true, 0.6, false)).toBe(false)
    expect(gate.advance(work, true, 0.39, false)).toBe(false)
    expect(gate.advance(work, true, 0.01, false)).toBe(true)
  })

  it('pauses interaction progress while an immediate reflex owns presentation', () => {
    const gate = new WorldActionInteractionGate()
    expect(gate.advance(work, true, 0.75, false)).toBe(false)
    expect(gate.advance(work, true, 2, true)).toBe(false)
    expect(gate.advance(work, true, 0.25, false)).toBe(true)
  })

  it('resets on replacement and immediately completes a target without interaction', () => {
    const gate = new WorldActionInteractionGate()
    expect(gate.advance(work, true, 0.75, false)).toBe(false)
    const replacement = { ...work, actionId: 'work-2' }
    expect(gate.advance(replacement, true, 0.25, false)).toBe(false)
    expect(gate.advance({ ...replacement, actionId: 'move-1', interactionTarget: undefined },
      true, 0, false)).toBe(true)
    expect(gate.advance({ ...replacement, actionId: 'look-1', activity: 'look_outside' },
      true, 0, false)).toBe(true)
  })
})
