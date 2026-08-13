import { describe, expect, it, vi } from 'vitest'
import { WorldActionAckOutbox, type WorldActionAckStorage } from './world-action-ack-outbox'
import { WorldActionAckWorker } from './world-action-ack-worker'

class MemoryStorage implements WorldActionAckStorage {
  private readonly values = new Map<string, string>()
  setCalls = 0
  failSet = false
  getItem(key: string) { return this.values.get(key) ?? null }
  setItem(key: string, value: string) {
    this.setCalls += 1
    if (this.failSet) throw new Error('storage unavailable')
    this.values.set(key, value)
  }
  removeItem(key: string) { this.values.delete(key) }
}

const command = (actionId: string) => ({
  worldId: 'gahyeon-home',
  request: {
    installationId: 'install-1', actionId, expectedRevision: 7,
    finalPosition: { x: 7, y: 0, z: -2 },
  },
})

describe('WorldActionAckWorker', () => {
  it('drains restored work and removes only a terminal Core result', async () => {
    vi.useFakeTimers()
    const outbox = new WorldActionAckOutbox(new MemoryStorage())
    outbox.enqueue(command('action-1'), Date.now())
    const client = { completeWorldAction: vi.fn().mockResolvedValue({ result: 'COMMITTED' }) }
    const worker = new WorldActionAckWorker(outbox, client)

    worker.start()
    await vi.runAllTimersAsync()

    expect(client.completeWorldAction).toHaveBeenCalledWith(
      'gahyeon-home', command('action-1').request,
    )
    expect(outbox.size()).toBe(0)
    worker.stop()
    vi.useRealTimers()
  })

  it('retains network and rejection outcomes with persisted exponential retry', async () => {
    vi.useFakeTimers()
    const outbox = new WorldActionAckOutbox(new MemoryStorage())
    const client = { completeWorldAction: vi.fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ result: 'STALE' })
      .mockResolvedValueOnce({ result: 'DUPLICATE' }) }
    const worker = new WorldActionAckWorker(outbox, client)
    worker.start()
    worker.enqueue(command('action-1'))

    await vi.advanceTimersByTimeAsync(0)
    expect(client.completeWorldAction).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(249)
    expect(client.completeWorldAction).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(client.completeWorldAction).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(499)
    expect(client.completeWorldAction).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(client.completeWorldAction).toHaveBeenCalledTimes(3)
    expect(outbox.size()).toBe(0)
    worker.stop()
    vi.useRealTimers()
  })

  it('reconciles an authoritative result and fences the late network response', async () => {
    vi.useFakeTimers()
    let resolve!: (value: { result: 'COMMITTED' }) => void
    const pending = new Promise<{ result: 'COMMITTED' }>(done => { resolve = done })
    const outbox = new WorldActionAckOutbox(new MemoryStorage())
    const client = { completeWorldAction: vi.fn().mockReturnValue(pending) }
    const worker = new WorldActionAckWorker(outbox, client)
    worker.start()
    worker.enqueue(command('action-1'))
    await vi.advanceTimersByTimeAsync(0)

    expect(worker.reconcileActionResult('action-1').status).toBe('removed')
    resolve({ result: 'COMMITTED' })
    await vi.runAllTimersAsync()

    expect(outbox.size()).toBe(0)
    expect(client.completeWorldAction).toHaveBeenCalledTimes(1)
    worker.stop()
    vi.useRealTimers()
  })

  it('does not mutate durable state after the worker is stopped mid-request', async () => {
    vi.useFakeTimers()
    let resolve!: (value: { result: 'COMMITTED' }) => void
    const pending = new Promise<{ result: 'COMMITTED' }>(done => { resolve = done })
    const outbox = new WorldActionAckOutbox(new MemoryStorage())
    const client = { completeWorldAction: vi.fn().mockReturnValue(pending) }
    const worker = new WorldActionAckWorker(outbox, client)
    worker.start()
    worker.enqueue(command('action-1'))
    await vi.advanceTimersByTimeAsync(0)

    worker.stop()
    resolve({ result: 'COMMITTED' })
    await Promise.resolve()
    await Promise.resolve()

    expect(outbox.has('action-1')).toBe(true)
    vi.useRealTimers()
  })

  it('delivers later due work while a non-terminal rejection remains queued', async () => {
    vi.useFakeTimers()
    const outbox = new WorldActionAckOutbox(new MemoryStorage())
    const client = { completeWorldAction: vi.fn()
      .mockResolvedValueOnce({ result: 'STALE' })
      .mockResolvedValueOnce({ result: 'COMMITTED' }) }
    const worker = new WorldActionAckWorker(outbox, client)
    worker.start()
    worker.enqueue(command('stale-action'))
    worker.enqueue(command('valid-action'))

    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(1)

    expect(client.completeWorldAction).toHaveBeenCalledTimes(2)
    expect(outbox.has('stale-action')).toBe(true)
    expect(outbox.has('valid-action')).toBe(false)
    worker.stop()
    vi.useRealTimers()
  })

  it('stops and reports fatal durability failure without retrying a broken store', async () => {
    vi.useFakeTimers()
    const storage = new MemoryStorage()
    const outbox = new WorldActionAckOutbox(storage)
    outbox.enqueue(command('action-1'), Date.now())
    storage.failSet = true
    const client = { completeWorldAction: vi.fn() }
    const worker = new WorldActionAckWorker(outbox, client)
    const durabilityFailure = vi.fn()
    worker.onDurabilityFailure(durabilityFailure)

    worker.start()
    await vi.advanceTimersByTimeAsync(0)
    expect(storage.setCalls).toBe(2)
    expect(durabilityFailure).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(10_000)
    expect(storage.setCalls).toBe(2)
    expect(client.completeWorldAction).not.toHaveBeenCalled()
    worker.stop()
    vi.useRealTimers()
  })

  it('reports a corrupt restored snapshot immediately and never starts', async () => {
    vi.useFakeTimers()
    const storage = new MemoryStorage()
    storage.setItem('gahyeon.world-action-ack-outbox.v1', '{not-json')
    const outbox = new WorldActionAckOutbox(storage)
    const client = { completeWorldAction: vi.fn() }
    const worker = new WorldActionAckWorker(outbox, client)
    const durabilityFailure = vi.fn()

    worker.onDurabilityFailure(durabilityFailure)
    worker.start()
    await vi.runAllTimersAsync()

    expect(durabilityFailure).toHaveBeenCalledTimes(1)
    expect(client.completeWorldAction).not.toHaveBeenCalled()
    vi.useRealTimers()
  })
})
