import { describe, expect, it } from 'vitest'
import { initialStageState } from './stage-state'
import { initialNavigationPath, presentationState, stageDestination } from './three-stage'

describe('Desktop world-action presentation', () => {
  const pending = {
    ...initialStageState,
    pendingWorldAction: {
      actionId: 'action-18',
      expectedRevision: 0,
      room: 'workspace',
      position: { x: 7.5, y: 0, z: -2.4 },
      activity: 'work',
      interactionTarget: 'desk',
    },
  }

  it('moves toward the Core target before the authoritative state commits', () => {
    expect(stageDestination(pending)).toEqual({
      room: 'workspace',
      position: { x: 7.5, y: 0, z: -2.4 },
    })
    expect(presentationState(pending, true).activity).toBe('walk')
    expect(presentationState(pending, false).activity).toBe('work')
  })

  it('creates a route when the target arrived before the stage mounted', () => {
    const path = initialNavigationPath(pending)
    expect(path.length).toBeGreaterThan(0)
    expect(path.at(-1)).toEqual(pending.pendingWorldAction.position)
  })

  it('lets immediate listening behavior pause an autonomous action', () => {
    const listening = { ...pending, activity: 'listening' }
    expect(presentationState(listening, true)).toBe(listening)
  })
})
