import { describe, expect, it } from 'vitest'
import { RecentRequestRegistry } from './recent-request-registry'

describe('RecentRequestRegistry', () => {
  it('deduplicates inside the TTL and expires without browser timers', () => {
    let now = 100
    const registry = new RecentRequestRegistry(1_000, 8, () => now)
    registry.add('request-1')
    expect(registry.has('request-1')).toBe(true)
    now = 1_100
    expect(registry.has('request-1')).toBe(false)
    expect(registry.size()).toBe(0)
  })

  it('evicts oldest identities at a strict capacity', () => {
    let now = 0
    const registry = new RecentRequestRegistry(10_000, 3, () => now)
    for (const requestId of ['one', 'two', 'three', 'four']) {
      registry.add(requestId)
      now++
    }
    expect(registry.size()).toBe(3)
    expect(registry.has('one')).toBe(false)
    expect(registry.has('two')).toBe(true)
  })

  it('refreshes a repeated identity without growing the registry', () => {
    let now = 0
    const registry = new RecentRequestRegistry(100, 3, () => now)
    registry.add('same')
    now = 50
    registry.add('same')
    expect(registry.size()).toBe(1)
    now = 120
    expect(registry.has('same')).toBe(true)
  })

  it('rejects unsafe bounds', () => {
    expect(() => new RecentRequestRegistry(0, 1)).toThrow()
    expect(() => new RecentRequestRegistry(1, 0)).toThrow()
  })
})
