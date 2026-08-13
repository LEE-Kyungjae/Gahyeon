/** Deterministic bounded retry delay; reset after any successfully delivered server event. */
export class ReconnectBackoff {
  private failures = 0

  constructor(
    private readonly minimumMs = 250,
    private readonly maximumMs = 5_000,
  ) {
    if (!Number.isFinite(minimumMs) || !Number.isFinite(maximumMs)
      || minimumMs < 1 || maximumMs < minimumMs) {
      throw new Error('invalid reconnect backoff bounds')
    }
  }

  nextDelayMs() {
    const delay = Math.min(this.maximumMs, this.minimumMs * 2 ** this.failures)
    this.failures = Math.min(this.failures + 1, 30)
    return delay
  }

  reset() {
    this.failures = 0
  }
}

export function abortableDelay(milliseconds: number, signal: AbortSignal) {
  if (signal.aborted) return Promise.resolve()
  return new Promise<void>((resolve) => {
    const timer = setTimeout(done, milliseconds)
    signal.addEventListener('abort', done, { once: true })

    function done() {
      clearTimeout(timer)
      signal.removeEventListener('abort', done)
      resolve()
    }
  })
}
