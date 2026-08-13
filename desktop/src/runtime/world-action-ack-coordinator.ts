import type { WorldActionAckCommand } from './world-action-ack-outbox'

interface AckWorker {
  start(): void
  stop(): void
  enqueue(command: WorldActionAckCommand): { accepted: boolean }
  reconcileActionResult(actionId: string): unknown
  onDurabilityFailure?(listener: () => void): () => void
}

interface PresenceLease {
  start(): void
  stop(): Promise<void>
}

/** Couples ACK durability failure to headless fallback without losing remount recovery. */
export class WorldActionAckCoordinator {
  private rendererPresent = false
  private durabilityBlocked = false
  private readonly blockedActionIds = new Set<string>()

  constructor(
    private readonly worker: AckWorker,
    private readonly presence: PresenceLease,
  ) {
    this.worker.onDurabilityFailure?.(() => this.blockForDurabilityFailure())
  }

  start() {
    if (!this.durabilityBlocked) this.worker.start()
  }
  stop() {
    this.worker.stop()
    this.rendererPresent = false
    void this.presence.stop()
  }

  enqueue(command: WorldActionAckCommand) {
    if (this.durabilityBlocked) return false
    const result = this.worker.enqueue(command)
    if (result.accepted) {
      if (this.blockedActionIds.delete(command.request.actionId)) this.resumeIfUnblocked()
      return true
    }
    if (this.durabilityBlocked) return false
    this.blockedActionIds.add(command.request.actionId)
    this.worker.stop()
    void this.presence.stop()
    return false
  }

  reconcile(actionId: string) {
    this.worker.reconcileActionResult(actionId)
    if (this.durabilityBlocked) return
    if (!this.blockedActionIds.delete(actionId)) return
    this.resumeIfUnblocked()
  }

  setRendererPresent(present: boolean) {
    this.rendererPresent = present
    if (present && !this.durabilityBlocked && this.blockedActionIds.size === 0) {
      this.worker.start()
      this.presence.start()
    }
    if (!present) void this.presence.stop()
  }

  rendererAvailable() {
    return this.rendererPresent && !this.durabilityBlocked && this.blockedActionIds.size === 0
  }

  failDurability() {
    this.blockForDurabilityFailure()
  }

  private resumeIfUnblocked() {
    if (this.durabilityBlocked || this.blockedActionIds.size > 0 || !this.rendererPresent) return
    this.worker.start()
    this.presence.start()
  }

  private blockForDurabilityFailure() {
    if (this.durabilityBlocked) return
    this.durabilityBlocked = true
    this.worker.stop()
    void this.presence.stop()
  }
}
