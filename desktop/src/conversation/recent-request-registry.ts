/** Bounded TTL deduplication without allocating one browser timer per request. */
export class RecentRequestRegistry {
  private readonly entries = new Map<string, number>()

  constructor(
    private readonly ttlMs = 30_000,
    private readonly capacity = 2_048,
    private readonly now: () => number = () => performance.now(),
  ) {
    if (!Number.isFinite(ttlMs) || ttlMs <= 0) throw new Error('request TTL must be positive')
    if (!Number.isSafeInteger(capacity) || capacity < 1) throw new Error('request capacity must be positive')
  }

  add(requestId: string) {
    if (!requestId) return
    const observedAt = this.now()
    this.prune(observedAt)
    this.entries.delete(requestId)
    this.entries.set(requestId, observedAt + this.ttlMs)
    while (this.entries.size > this.capacity) {
      const oldest = this.entries.keys().next().value
      if (oldest === undefined) break
      this.entries.delete(oldest)
    }
  }

  has(requestId: string) {
    const observedAt = this.now()
    this.prune(observedAt)
    const expiry = this.entries.get(requestId)
    if (expiry === undefined || expiry <= observedAt) {
      this.entries.delete(requestId)
      return false
    }
    return true
  }

  size() {
    this.prune(this.now())
    return this.entries.size
  }

  private prune(observedAt: number) {
    for (const [requestId, expiry] of this.entries) {
      if (expiry > observedAt) continue
      this.entries.delete(requestId)
    }
  }
}
