import type {
  WorldActionCompletionRequest,
  WorldActionCompletionResponse,
} from '../gahyeon-api'
import {
  WorldActionAckOutbox,
  type WorldActionAckCommand,
  type WorldActionAckReconcileResult,
} from './world-action-ack-outbox'

const MAXIMUM_TIMER_DELAY_MILLIS = 5_000

export interface WorldActionAckClient {
  completeWorldAction(
    worldId: string,
    request: WorldActionCompletionRequest,
  ): Promise<WorldActionCompletionResponse>
}

/** Serial durable delivery loop for renderer world-action acknowledgements. */
export class WorldActionAckWorker {
  private running = false
  private timer?: ReturnType<typeof setTimeout>
  private pumping = false
  private readonly durabilityFailureListeners = new Set<() => void>()
  private durabilityFailureReported = false

  constructor(
    private readonly outbox: WorldActionAckOutbox,
    private readonly client: WorldActionAckClient,
    private readonly now: () => number = () => Date.now(),
  ) {}

  onDurabilityFailure(listener: () => void) {
    this.durabilityFailureListeners.add(listener)
    if (!this.outbox.isDurabilityHealthy()) this.reportDurabilityFailure()
    return () => this.durabilityFailureListeners.delete(listener)
  }

  start() {
    if (this.running || !this.requireHealthyDurability()) return
    this.running = true
    this.schedule(0)
  }

  stop() {
    this.running = false
    if (this.timer !== undefined) clearTimeout(this.timer)
    this.timer = undefined
    const activeActionId = this.outbox.activeClaimActionId()
    if (activeActionId) this.outbox.releaseClaim(activeActionId)
  }

  enqueue(command: WorldActionAckCommand) {
    const result = this.outbox.enqueue(command, this.now())
    this.requireHealthyDurability()
    if (result.accepted) this.schedule(0)
    return result
  }

  reconcileActionResult(actionId: string): WorldActionAckReconcileResult {
    const result = this.outbox.reconcileActionResult(actionId)
    this.requireHealthyDurability()
    if (result.removed) this.schedule(0)
    return result
  }

  private schedule(delayMillis: number) {
    if (!this.running) return
    if (this.timer !== undefined) clearTimeout(this.timer)
    this.timer = setTimeout(() => {
      this.timer = undefined
      void this.pump()
    }, Math.min(MAXIMUM_TIMER_DELAY_MILLIS, Math.max(0, delayMillis)))
  }

  private async pump() {
    if (!this.running || this.pumping) return
    this.pumping = true
    try {
      const observedAt = this.now()
      const claimed = this.outbox.claimDue(observedAt)
      if (claimed.status !== 'claimed') {
        if (claimed.status === 'storage-error') {
          this.requireHealthyDurability()
          return
        }
        if (claimed.status === 'busy') {
          this.schedule(MAXIMUM_TIMER_DELAY_MILLIS)
          return
        }
        this.scheduleNext(observedAt)
        return
      }
      let outcome: WorldActionCompletionResponse['result'] | 'NETWORK_FAILURE'
      try {
        const response = await this.client.completeWorldAction(
          claimed.claim.command.worldId,
          claimed.claim.command.request,
        )
        outcome = response.result
      }
      catch {
        outcome = 'NETWORK_FAILURE'
      }
      if (!this.running) {
        this.outbox.releaseClaim(claimed.claim.actionId)
        return
      }
      this.outbox.finish(claimed.claim, outcome)
      if (!this.requireHealthyDurability()) return
      this.scheduleNext(this.now())
    }
    finally {
      this.pumping = false
    }
  }

  private scheduleNext(now: number) {
    const nextAttemptAt = this.outbox.snapshot().reduce<number | undefined>(
      (earliest, entry) => earliest === undefined
        ? entry.nextAttemptAt
        : Math.min(earliest, entry.nextAttemptAt),
      undefined,
    )
    if (nextAttemptAt === undefined) return
    this.schedule(Math.max(0, nextAttemptAt - now))
  }

  private requireHealthyDurability() {
    if (this.outbox.isDurabilityHealthy()) return true
    this.stop()
    this.reportDurabilityFailure()
    return false
  }

  private reportDurabilityFailure() {
    if (this.durabilityFailureReported) return
    this.durabilityFailureReported = true
    for (const listener of this.durabilityFailureListeners) listener()
  }
}
