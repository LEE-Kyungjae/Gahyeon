import type { PendingWorldAction } from './stage-state'

const INTERACTION_SETTLE_SECONDS = 1
const completionGatedActivities = new Set(['sit', 'read', 'work'])

/** Holds an ACK until an interaction has been presented for a visible settle window. */
export class WorldActionInteractionGate {
  private actionId?: string
  private elapsedSeconds = 0

  advance(
    action: PendingWorldAction | undefined,
    arrived: boolean,
    deltaSeconds: number,
    paused: boolean,
  ) {
    if (!action) {
      this.reset()
      return false
    }
    if (action.actionId !== this.actionId) {
      this.actionId = action.actionId
      this.elapsedSeconds = 0
    }
    if (!arrived || paused) return false
    if (!action.interactionTarget || !completionGatedActivities.has(action.activity)) return true
    this.elapsedSeconds += Number.isFinite(deltaSeconds) && deltaSeconds > 0 ? deltaSeconds : 0
    return this.elapsedSeconds + Number.EPSILON >= INTERACTION_SETTLE_SECONDS
  }

  reset() {
    this.actionId = undefined
    this.elapsedSeconds = 0
  }
}
