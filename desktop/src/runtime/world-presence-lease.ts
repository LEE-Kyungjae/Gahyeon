export interface WorldPresenceClient {
  heartbeatWorldPresence(worldId: string, installationId: string): Promise<void>
  releaseWorldPresence(worldId: string, installationId: string): Promise<void>
}

/** Renderer-lifecycle lease; stop fences any in-flight heartbeat before release. */
export class WorldPresenceLease {
  private running = false
  private generation = 0
  private timer?: ReturnType<typeof setInterval>
  private inFlight?: Promise<void>
  private releaseInFlight?: Promise<void>
  private released = true

  constructor(
    private readonly client: WorldPresenceClient,
    private readonly worldId: string,
    private readonly installationId: string,
    private readonly intervalMillis = 5_000,
  ) {}

  start() {
    if (this.running) return
    this.running = true
    this.released = false
    const generation = ++this.generation
    if (this.releaseInFlight) {
      const release = this.releaseInFlight
      void release.finally(() => {
        if (this.releaseInFlight === release) this.releaseInFlight = undefined
        void this.renew(generation)
      })
    }
    else void this.renew(generation)
    this.timer = setInterval(() => void this.renew(generation), this.intervalMillis)
  }

  async stop() {
    if (!this.running || this.released) return
    this.running = false
    const generation = ++this.generation
    if (this.timer !== undefined) clearInterval(this.timer)
    this.timer = undefined
    await this.inFlight
    if (this.running || generation !== this.generation || this.released) return
    this.released = true
    const release = Promise.resolve()
      .then(() => this.client.releaseWorldPresence(this.worldId, this.installationId))
      .catch(() => undefined)
    this.releaseInFlight = release
    await release
    if (this.releaseInFlight === release) this.releaseInFlight = undefined
  }

  private renew(generation: number) {
    if (!this.running || generation !== this.generation
        || this.inFlight || this.releaseInFlight) return
    const heartbeat = Promise.resolve()
      .then(() => this.client.heartbeatWorldPresence(this.worldId, this.installationId))
      .catch(() => undefined)
    const tracked = heartbeat.finally(() => {
      if (this.inFlight === tracked) this.inFlight = undefined
    })
    this.inFlight = tracked
    return tracked
  }
}
