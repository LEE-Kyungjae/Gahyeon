import { describe, expect, it } from 'vitest'
import {
  WORLD_ACTION_ACK_OUTBOX_CAPACITY,
  WorldActionAckOutbox,
  type WorldActionAckCommand,
  type WorldActionAckStorage,
} from './world-action-ack-outbox'

class MemoryStorage implements WorldActionAckStorage {
  readonly values = new Map<string, string>()
  setCalls = 0
  failGet = false
  failSet = false
  failRemove = false

  getItem(key: string) {
    if (this.failGet) throw new Error('get failed')
    return this.values.get(key) ?? null
  }

  setItem(key: string, value: string) {
    this.setCalls += 1
    if (this.failSet) throw new Error('set failed')
    this.values.set(key, value)
  }

  removeItem(key: string) {
    if (this.failRemove) throw new Error('remove failed')
    this.values.delete(key)
  }
}

const command = (actionId: string, x = 1): WorldActionAckCommand => ({
  worldId: 'gahyeon-home',
  request: {
    installationId: 'desktop-installation-1',
    actionId,
    expectedRevision: 7,
    finalPosition: { x, y: 2, z: 3 },
  },
})

describe('WorldActionAckOutbox durability', () => {
  it('persists synchronously before accepting and keeps memory unchanged on storage failure', () => {
    const storage = new MemoryStorage()
    const outbox = new WorldActionAckOutbox(storage)

    expect(outbox.enqueue(command('action-1'), 1_000)).toEqual({
      accepted: true,
      status: 'accepted',
    })
    expect(storage.setCalls).toBe(1)
    expect(storage.values.values().next().value).toContain('action-1')
    expect(outbox.size()).toBe(1)

    storage.failSet = true
    expect(outbox.enqueue(command('action-2'), 1_000)).toEqual({
      accepted: false,
      status: 'storage-error',
    })
    expect(outbox.isDurabilityHealthy()).toBe(false)
    expect(outbox.size()).toBe(1)
    expect(outbox.has('action-2')).toBe(false)
  })

  it('accepts an exact duplicate idempotently, rejects conflicting reuse, and caps at 32 ids', () => {
    const outbox = new WorldActionAckOutbox(new MemoryStorage())
    expect(outbox.enqueue(command('action-0'), 0).status).toBe('accepted')
    expect(outbox.enqueue(command('action-0'), 50)).toEqual({
      accepted: true,
      status: 'duplicate',
    })
    expect(outbox.enqueue(command('action-0', 99), 50)).toEqual({
      accepted: false,
      status: 'conflict',
    })

    for (let index = 1; index < WORLD_ACTION_ACK_OUTBOX_CAPACITY; index += 1) {
      expect(outbox.enqueue(command(`action-${index}`), index).accepted).toBe(true)
    }
    expect(outbox.size()).toBe(32)
    expect(outbox.enqueue(command('overflow'), 100)).toEqual({
      accepted: false,
      status: 'full',
    })
  })

  it('selects due work deterministically without letting a retained retry starve later work', () => {
    const storage = new MemoryStorage()
    const outbox = new WorldActionAckOutbox(storage)
    outbox.enqueue(command('first'), 1_000)
    outbox.enqueue(command('second'), 1_000)

    expect(outbox.due(999)).toBeUndefined()
    expect(outbox.due(1_000)?.command.request.actionId).toBe('first')

    const claimed = outbox.claimDue(1_000)
    expect(claimed.status).toBe('claimed')
    if (claimed.status !== 'claimed') throw new Error('expected claim')
    expect(claimed.claim.attempts).toBe(1)
    expect(claimed.claim.nextAttemptAt).toBe(1_250)
    expect(outbox.claimDue(1_000)).toEqual({ status: 'busy' })

    const restored = new WorldActionAckOutbox(storage)
    expect(restored.snapshot()[0]).toMatchObject({ attempts: 1, nextAttemptAt: 1_250 })
    expect(outbox.finish(claimed.claim, 'NETWORK_FAILURE')).toEqual({
      finished: true,
      status: 'retained',
    })
    expect(outbox.due(1_249)?.command.request.actionId).toBe('second')
    const second = outbox.claimDue(1_000)
    if (second.status !== 'claimed') throw new Error('expected second claim')
    expect(second.claim.command.request.actionId).toBe('second')
    expect(outbox.finish(second.claim, 'DUPLICATE').status).toBe('removed')
    expect(outbox.due(1_249)).toBeUndefined()
    expect(outbox.due(1_250)?.command.request.actionId).toBe('first')
  })

  it('persists exponential retry attempts from 250ms through the 5s ceiling', () => {
    const storage = new MemoryStorage()
    let outbox = new WorldActionAckOutbox(storage)
    outbox.enqueue(command('retry-me'), 10_000)
    const delays = [250, 500, 1_000, 2_000, 4_000, 5_000, 5_000]
    let now = 10_000

    for (const [index, delay] of delays.entries()) {
      const claimed = outbox.claimDue(now)
      expect(claimed.status).toBe('claimed')
      if (claimed.status !== 'claimed') throw new Error('expected claim')
      expect(claimed.claim.attempts).toBe(index + 1)
      expect(claimed.claim.nextAttemptAt).toBe(now + delay)
      expect(outbox.finish(claimed.claim, index % 2 === 0 ? 'STALE' : 'CONFLICT'))
        .toEqual({ finished: true, status: 'retained' })

      outbox = new WorldActionAckOutbox(storage)
      expect(outbox.snapshot()[0]).toMatchObject({
        attempts: index + 1,
        nextAttemptAt: now + delay,
      })
      now += delay
    }
  })

  it.each(['STALE', 'CONFLICT', 'INVALID', 'RECORDED_FAILURE'] as const)(
    'retains and retries a %s Core result',
    result => {
      const outbox = new WorldActionAckOutbox(new MemoryStorage())
      outbox.enqueue(command(`action-${result}`), 0)
      const claimed = outbox.claimDue(0)
      if (claimed.status !== 'claimed') throw new Error('expected claim')
      expect(outbox.finish(claimed.claim, result)).toEqual({
        finished: true,
        status: 'retained',
      })
      expect(outbox.has(`action-${result}`)).toBe(true)
      expect(outbox.due(249)).toBeUndefined()
      expect(outbox.due(250)).toBeDefined()
    },
  )

  it.each(['COMMITTED', 'DUPLICATE'] as const)(
    'removes only a %s response-terminal entry',
    result => {
      const storage = new MemoryStorage()
      const outbox = new WorldActionAckOutbox(storage)
      outbox.enqueue(command(`action-${result}`), 0)
      const claimed = outbox.claimDue(0)
      if (claimed.status !== 'claimed') throw new Error('expected claim')
      expect(outbox.finish(claimed.claim, result)).toEqual({
        finished: true,
        status: 'removed',
      })
      expect(outbox.size()).toBe(0)
      expect(new WorldActionAckOutbox(storage).size()).toBe(0)
    },
  )

  it('keeps a terminal response durable when removal persistence fails', () => {
    const storage = new MemoryStorage()
    const outbox = new WorldActionAckOutbox(storage)
    outbox.enqueue(command('action-1'), 0)
    const claimed = outbox.claimDue(0)
    if (claimed.status !== 'claimed') throw new Error('expected claim')
    storage.failSet = true

    expect(outbox.finish(claimed.claim, 'COMMITTED')).toEqual({
      finished: false,
      status: 'storage-error',
    })
    expect(outbox.has('action-1')).toBe(true)
    storage.failSet = false
    expect(outbox.claimDue(250)).toEqual({ status: 'storage-error' })
    expect(outbox.isDurabilityHealthy()).toBe(false)
  })

  it('reconciles an authoritative action result and fences a late network completion', () => {
    const storage = new MemoryStorage()
    const outbox = new WorldActionAckOutbox(storage)
    outbox.enqueue(command('action-1'), 0)
    const claimed = outbox.claimDue(0)
    if (claimed.status !== 'claimed') throw new Error('expected claim')

    expect(outbox.reconcileActionResult('action-1')).toEqual({
      removed: true,
      status: 'removed',
    })
    expect(outbox.finish(claimed.claim, 'COMMITTED')).toEqual({
      finished: false,
      status: 'invalid-claim',
    })
    expect(outbox.reconcileActionResult('action-1')).toEqual({
      removed: false,
      status: 'missing',
    })
    expect(new WorldActionAckOutbox(storage).size()).toBe(0)
  })
})

describe('WorldActionAckOutbox recovery and validation', () => {
  it.each([
    '{not-json',
    JSON.stringify({ schema: 'wrong', version: 1, entries: [] }),
    JSON.stringify({ schema: 'gahyeon.world-action-ack-outbox', version: 2, entries: [] }),
    JSON.stringify({
      schema: 'gahyeon.world-action-ack-outbox',
      version: 1,
      entries: Array.from({ length: 33 }, (_, index) => ({
        command: command(`action-${index}`), attempts: 0, nextAttemptAt: 0,
      })),
    }),
  ])('fails closed without throwing for a corrupt or incompatible snapshot', raw => {
    const storage = new MemoryStorage()
    storage.values.set('gahyeon.world-action-ack-outbox.v1', raw)
    const outbox = new WorldActionAckOutbox(storage)
    expect(outbox.size()).toBe(0)
    expect(outbox.isDurabilityHealthy()).toBe(false)
    expect(outbox.enqueue(command('must-not-overwrite'), 0)).toEqual({
      accepted: false,
      status: 'storage-error',
    })
    expect(storage.values.get('gahyeon.world-action-ack-outbox.v1')).toBe(raw)
  })

  it('bounds oversized storage before parsing and tolerates unavailable storage', () => {
    const oversized = new MemoryStorage()
    oversized.values.set(
      'gahyeon.world-action-ack-outbox.v1',
      `{"padding":"${'x'.repeat(70_000)}"}`,
    )
    const oversizedOutbox = new WorldActionAckOutbox(oversized)
    expect(oversizedOutbox.size()).toBe(0)
    expect(oversizedOutbox.isDurabilityHealthy()).toBe(false)

    const unavailable = new MemoryStorage()
    unavailable.failGet = true
    unavailable.failSet = true
    unavailable.failRemove = true
    expect(() => new WorldActionAckOutbox(unavailable)).not.toThrow()
    const outbox = new WorldActionAckOutbox(unavailable)
    expect(outbox.isDurabilityHealthy()).toBe(false)
    expect(outbox.enqueue(command('cannot-persist'), 0)).toEqual({
      accepted: false,
      status: 'storage-error',
    })

    const unreadable = new MemoryStorage()
    unreadable.values.set('gahyeon.world-action-ack-outbox.v1', 'unknown durable state')
    unreadable.failGet = true
    expect(new WorldActionAckOutbox(unreadable).enqueue(command('must-not-overwrite'), 0))
      .toEqual({ accepted: false, status: 'storage-error' })
    expect(unreadable.setCalls).toBe(0)
  })

  it('rejects malformed commands and times without throwing', () => {
    const outbox = new WorldActionAckOutbox(new MemoryStorage())
    const invalidCommands = [
      { ...command('ok'), worldId: '' },
      { ...command('ok'), request: { ...command('ok').request, actionId: '' } },
      {
        ...command('ok'),
        request: {
          ...command('ok').request,
          finalPosition: { x: Number.NaN, y: 0, z: 0 },
        },
      },
    ]
    for (const invalid of invalidCommands) {
      expect(outbox.enqueue(invalid, 0)).toEqual({ accepted: false, status: 'invalid' })
    }
    expect(outbox.enqueue(command('ok'), -1)).toEqual({
      accepted: false,
      status: 'invalid',
    })
    expect(outbox.claimDue(Number.NaN)).toEqual({ status: 'invalid-time' })
    expect(outbox.reconcileActionResult('')).toEqual({ removed: false, status: 'invalid' })
  })
})
