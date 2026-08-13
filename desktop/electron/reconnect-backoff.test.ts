import { afterEach, describe, expect, it, vi } from 'vitest'
import { abortableDelay, ReconnectBackoff } from './reconnect-backoff.js'

afterEach(() => {
  vi.useRealTimers()
})

describe('ReconnectBackoff', () => {
  it('grows exponentially, caps, and resets after healthy traffic', () => {
    const backoff = new ReconnectBackoff(250, 1_000)
    expect([
      backoff.nextDelayMs(),
      backoff.nextDelayMs(),
      backoff.nextDelayMs(),
      backoff.nextDelayMs(),
    ]).toEqual([250, 500, 1_000, 1_000])
    backoff.reset()
    expect(backoff.nextDelayMs()).toBe(250)
  })

  it('rejects invalid bounds', () => {
    expect(() => new ReconnectBackoff(0, 10)).toThrow()
    expect(() => new ReconnectBackoff(10, 5)).toThrow()
  })

  it('releases a pending delay immediately on unsubscribe', async () => {
    vi.useFakeTimers()
    const controller = new AbortController()
    const pending = abortableDelay(10_000, controller.signal)
    expect(vi.getTimerCount()).toBe(1)
    controller.abort()
    await pending
    expect(vi.getTimerCount()).toBe(0)
  })
})
