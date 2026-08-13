import { describe, expect, it, vi } from 'vitest'
import { WorldPresenceLease } from './world-presence-lease'

describe('WorldPresenceLease', () => {
  it('renews immediately and periodically, then releases exactly once', async () => {
    vi.useFakeTimers()
    const client = {
      heartbeatWorldPresence: vi.fn().mockResolvedValue(undefined),
      releaseWorldPresence: vi.fn().mockResolvedValue(undefined),
    }
    const lease = new WorldPresenceLease(client, 'gahyeon-home', 'install-1', 5_000)

    lease.start()
    await Promise.resolve()
    expect(client.heartbeatWorldPresence).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(5_000)
    expect(client.heartbeatWorldPresence).toHaveBeenCalledTimes(2)

    await lease.stop()
    await lease.stop()
    expect(client.releaseWorldPresence).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(10_000)
    expect(client.heartbeatWorldPresence).toHaveBeenCalledTimes(2)
    vi.useRealTimers()
  })

  it('waits for an in-flight heartbeat before releasing and never renews after release', async () => {
    vi.useFakeTimers()
    let resolveHeartbeat!: () => void
    const heartbeat = new Promise<void>(resolve => { resolveHeartbeat = resolve })
    const client = {
      heartbeatWorldPresence: vi.fn().mockReturnValue(heartbeat),
      releaseWorldPresence: vi.fn().mockResolvedValue(undefined),
    }
    const lease = new WorldPresenceLease(client, 'gahyeon-home', 'install-1', 5_000)

    lease.start()
    await Promise.resolve()
    const stopping = lease.stop()
    expect(client.releaseWorldPresence).not.toHaveBeenCalled()
    resolveHeartbeat()
    await stopping
    expect(client.releaseWorldPresence).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(10_000)
    expect(client.heartbeatWorldPresence).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
  })

  it('renews after a rapid remount races an already-started release', async () => {
    vi.useFakeTimers()
    let resolveRelease!: () => void
    const release = new Promise<void>(resolve => { resolveRelease = resolve })
    const client = {
      heartbeatWorldPresence: vi.fn().mockResolvedValue(undefined),
      releaseWorldPresence: vi.fn().mockReturnValue(release),
    }
    const lease = new WorldPresenceLease(client, 'gahyeon-home', 'install-1', 5_000)

    lease.start()
    await vi.advanceTimersByTimeAsync(0)
    const stopping = lease.stop()
    await vi.advanceTimersByTimeAsync(0)
    expect(client.releaseWorldPresence).toHaveBeenCalledTimes(1)

    lease.start()
    await vi.advanceTimersByTimeAsync(0)
    expect(client.heartbeatWorldPresence).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(5_000)
    expect(client.heartbeatWorldPresence).toHaveBeenCalledTimes(1)
    resolveRelease()
    await stopping
    await vi.advanceTimersByTimeAsync(0)

    expect(client.heartbeatWorldPresence).toHaveBeenCalledTimes(2)
    await lease.stop()
    vi.useRealTimers()
  })
})
