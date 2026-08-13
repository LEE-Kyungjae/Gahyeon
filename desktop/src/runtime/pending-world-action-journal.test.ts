import { describe, expect, it } from 'vitest'
import type { PendingWorldAction } from '../stage/stage-state'
import {
  PENDING_WORLD_ACTION_JOURNAL_CAPACITY,
  PENDING_WORLD_ACTION_JOURNAL_STORAGE_KEY,
  PendingWorldActionJournal,
  type PendingWorldActionJournalStorage,
} from './pending-world-action-journal'

class MemoryStorage implements PendingWorldActionJournalStorage {
  readonly values = new Map<string, string>()
  setCalls = 0
  removeCalls = 0
  failGet = false
  failSet = false

  getItem(key: string) {
    if (this.failGet) throw new Error('read failed')
    return this.values.get(key) ?? null
  }

  setItem(key: string, value: string) {
    this.setCalls += 1
    if (this.failSet) throw new Error('write failed')
    this.values.set(key, value)
  }

  removeItem(key: string) {
    this.removeCalls += 1
    this.values.delete(key)
  }
}

const action = (actionId = 'action-1', x = 7): PendingWorldAction => ({
  actionId,
  expectedRevision: 11,
  room: 'workspace',
  position: { x, y: 0, z: -2 },
  activity: 'work',
  interactionTarget: 'desk',
})

describe('PendingWorldActionJournal durability', () => {
  it('persists synchronously before confirming record and restores only the exact world', () => {
    const storage = new MemoryStorage()
    const journal = new PendingWorldActionJournal(storage)

    expect(journal.record('gahyeon-home', action())).toEqual({
      persisted: true,
      status: 'recorded',
    })
    expect(storage.setCalls).toBe(1)
    expect(JSON.parse(storage.values.get(PENDING_WORLD_ACTION_JOURNAL_STORAGE_KEY)!))
      .toEqual({
        schema: 'gahyeon.pending-world-action-journal',
        version: 1,
        entries: [{ worldId: 'gahyeon-home', action: action() }],
      })

    const restored = new PendingWorldActionJournal(storage)
    expect(restored.restore('gahyeon-home')).toEqual({
      restored: true,
      action: action(),
    })
    expect(restored.restore('another-world')).toEqual({
      restored: false,
      status: 'missing',
    })
    expect(restored.health()).toEqual({ healthy: true })
  })

  it('is idempotent for an exact duplicate and rejects conflicting world/action reuse', () => {
    const storage = new MemoryStorage()
    const journal = new PendingWorldActionJournal(storage)
    expect(journal.record('world-1', action())).toMatchObject({ persisted: true })

    expect(journal.record('world-1', action())).toEqual({
      persisted: true,
      status: 'duplicate',
    })
    expect(storage.setCalls).toBe(1)
    expect(journal.record('world-1', action('action-2'))).toEqual({
      persisted: false,
      status: 'conflict',
    })
    expect(journal.record('world-2', action())).toEqual({
      persisted: false,
      status: 'conflict',
    })
  })

  it('clears an authoritative terminal by action id and persists the removal', () => {
    const storage = new MemoryStorage()
    const journal = new PendingWorldActionJournal(storage)
    journal.record('world-1', action('action-1'))
    journal.record('world-2', action('action-2'))

    expect(journal.clear('action-1')).toEqual({
      persisted: true,
      status: 'cleared',
    })
    expect(journal.restore('world-1')).toEqual({ restored: false, status: 'missing' })
    expect(journal.restore('world-2')).toEqual({ restored: true, action: action('action-2') })
    expect(new PendingWorldActionJournal(storage).restore('world-1'))
      .toEqual({ restored: false, status: 'missing' })
    expect(journal.clear('not-recorded')).toEqual({
      persisted: true,
      status: 'missing',
    })
  })

  it('bounds the journal and validates exact action documents before writing', () => {
    const storage = new MemoryStorage()
    const journal = new PendingWorldActionJournal(storage)
    expect(journal.record('world-1', {
      ...action(),
      unexpected: true,
    } as PendingWorldAction)).toEqual({ persisted: false, status: 'invalid' })
    expect(journal.record('world-1', {
      ...action(),
      position: { x: Number.POSITIVE_INFINITY, y: 0, z: 0 },
    })).toEqual({ persisted: false, status: 'invalid' })
    expect(journal.record('w'.repeat(121), action())).toEqual({
      persisted: false,
      status: 'invalid',
    })

    for (let index = 0; index < PENDING_WORLD_ACTION_JOURNAL_CAPACITY; index += 1) {
      expect(journal.record(`world-${index}`, action(`action-${index}`)).persisted).toBe(true)
    }
    expect(journal.record('overflow-world', action('overflow-action'))).toEqual({
      persisted: false,
      status: 'full',
    })
  })
})

describe('PendingWorldActionJournal fail-closed health', () => {
  it.each([
    '{not-json',
    JSON.stringify({ schema: 'wrong', version: 1, entries: [] }),
    JSON.stringify({
      schema: 'gahyeon.pending-world-action-journal',
      version: 1,
      entries: [{ worldId: 'world-1', action: { ...action(), extra: true } }],
    }),
    `{"padding":"${'x'.repeat(40_000)}"}`,
  ])('marks corrupt or incompatible durable state unhealthy without deleting it', raw => {
    const storage = new MemoryStorage()
    storage.values.set(PENDING_WORLD_ACTION_JOURNAL_STORAGE_KEY, raw)

    const journal = new PendingWorldActionJournal(storage)

    expect(journal.health()).toEqual({ healthy: false, reason: 'corrupt-snapshot' })
    expect(journal.restore('world-1')).toEqual({ restored: false, status: 'unhealthy' })
    expect(journal.record('world-1', action())).toEqual({
      persisted: false,
      status: 'unhealthy',
    })
    expect(journal.clear('action-1')).toEqual({
      persisted: false,
      status: 'unhealthy',
    })
    expect(storage.values.get(PENDING_WORLD_ACTION_JOURNAL_STORAGE_KEY)).toBe(raw)
    expect(storage.removeCalls).toBe(0)
  })

  it('exposes a read failure so presence can remain suppressed', () => {
    const storage = new MemoryStorage()
    storage.failGet = true
    const journal = new PendingWorldActionJournal(storage)

    expect(journal.health()).toEqual({ healthy: false, reason: 'read-error' })
    expect(journal.restore('gahyeon-home')).toEqual({ restored: false, status: 'unhealthy' })
    expect(journal.record('gahyeon-home', action())).toEqual({
      persisted: false,
      status: 'unhealthy',
    })
    expect(storage.setCalls).toBe(0)
  })

  it('does not mutate memory after a write failure and remains fail-closed', () => {
    const storage = new MemoryStorage()
    const journal = new PendingWorldActionJournal(storage)
    journal.record('world-1', action('action-1'))
    storage.failSet = true

    expect(journal.record('world-2', action('action-2'))).toEqual({
      persisted: false,
      status: 'storage-error',
    })
    expect(journal.health()).toEqual({ healthy: false, reason: 'write-error' })
    expect(journal.restore('world-1')).toEqual({ restored: false, status: 'unhealthy' })

    storage.failSet = false
    expect(journal.clear('action-1')).toEqual({ persisted: false, status: 'unhealthy' })
    expect(new PendingWorldActionJournal(storage).restore('world-1'))
      .toEqual({ restored: true, action: action('action-1') })
  })
})
