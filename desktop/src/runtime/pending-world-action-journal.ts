import type { PendingWorldAction } from '../stage/stage-state'

export const PENDING_WORLD_ACTION_JOURNAL_CAPACITY = 8
export const PENDING_WORLD_ACTION_JOURNAL_STORAGE_KEY
  = 'gahyeon.pending-world-action-journal.v1'

const SNAPSHOT_SCHEMA = 'gahyeon.pending-world-action-journal'
const SNAPSHOT_VERSION = 1
const MAXIMUM_PERSISTED_CHARACTERS = 32 * 1024
const MAXIMUM_WORLD_ID_CHARACTERS = 120
const MAXIMUM_ACTION_ID_CHARACTERS = 80
const MAXIMUM_ROOM_CHARACTERS = 100
const MAXIMUM_ACTIVITY_CHARACTERS = 40
const MAXIMUM_INTERACTION_TARGET_CHARACTERS = 120
const MAXIMUM_WORLD_COORDINATE = 1_000_000

export interface PendingWorldActionJournalStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
}

export type PendingWorldActionJournalHealth =
  | { healthy: true }
  | {
    healthy: false
    reason: 'read-error' | 'corrupt-snapshot' | 'write-error'
  }

export type PendingWorldActionRecordResult =
  | { persisted: true, status: 'recorded' | 'duplicate' }
  | {
    persisted: false
    status: 'invalid' | 'conflict' | 'full' | 'storage-error' | 'unhealthy'
  }

export type PendingWorldActionClearResult =
  | { persisted: true, status: 'cleared' | 'missing' }
  | { persisted: false, status: 'invalid' | 'storage-error' | 'unhealthy' }

export type PendingWorldActionRestoreResult =
  | { restored: true, action: PendingWorldAction }
  | { restored: false, status: 'missing' | 'invalid' | 'unhealthy' }

interface JournalEntry {
  worldId: string
  action: PendingWorldAction
}

interface PersistedSnapshot {
  schema: typeof SNAPSHOT_SCHEMA
  version: typeof SNAPSHOT_VERSION
  entries: JournalEntry[]
}

/**
 * Synchronous write-ahead journal for Core transition targets.
 *
 * Callers can commit the matching durable event cursor only after `record`
 * returns `persisted: true`. Any unknown or failed durable state is sticky and
 * fail-closed for this instance, allowing the renderer to suppress presence
 * until a clean reload can prove recovery is safe.
 */
export class PendingWorldActionJournal {
  private entries: JournalEntry[] = []
  private unhealthyReason?: Exclude<PendingWorldActionJournalHealth, { healthy: true }>['reason']

  constructor(
    private readonly storage: PendingWorldActionJournalStorage,
    private readonly storageKey = PENDING_WORLD_ACTION_JOURNAL_STORAGE_KEY,
  ) {
    this.entries = this.readSnapshot()
  }

  health(): PendingWorldActionJournalHealth {
    return this.unhealthyReason
      ? { healthy: false, reason: this.unhealthyReason }
      : { healthy: true }
  }

  record(
    worldId: string,
    action: PendingWorldAction,
  ): PendingWorldActionRecordResult {
    if (this.unhealthyReason) return { persisted: false, status: 'unhealthy' }
    if (!validBoundedText(worldId, MAXIMUM_WORLD_ID_CHARACTERS)
        || !validAction(action)) return { persisted: false, status: 'invalid' }

    const worldEntry = this.entries.find(entry => entry.worldId === worldId)
    const actionEntry = this.entries.find(entry => entry.action.actionId === action.actionId)
    if (worldEntry || actionEntry) {
      return worldEntry === actionEntry && worldEntry !== undefined
        && equalAction(worldEntry.action, action)
        ? { persisted: true, status: 'duplicate' }
        : { persisted: false, status: 'conflict' }
    }
    if (this.entries.length >= PENDING_WORLD_ACTION_JOURNAL_CAPACITY) {
      return { persisted: false, status: 'full' }
    }

    const candidate = [...this.entries, { worldId, action: cloneAction(action) }]
    if (!this.persist(candidate)) return { persisted: false, status: 'storage-error' }
    this.entries = candidate
    return { persisted: true, status: 'recorded' }
  }

  clear(actionId: string): PendingWorldActionClearResult {
    if (this.unhealthyReason) return { persisted: false, status: 'unhealthy' }
    if (!validBoundedText(actionId, MAXIMUM_ACTION_ID_CHARACTERS)) {
      return { persisted: false, status: 'invalid' }
    }
    if (!this.entries.some(entry => entry.action.actionId === actionId)) {
      return { persisted: true, status: 'missing' }
    }

    const candidate = this.entries.filter(entry => entry.action.actionId !== actionId)
    if (!this.persist(candidate)) return { persisted: false, status: 'storage-error' }
    this.entries = candidate
    return { persisted: true, status: 'cleared' }
  }

  restore(worldId: string): PendingWorldActionRestoreResult {
    if (this.unhealthyReason) return { restored: false, status: 'unhealthy' }
    if (!validBoundedText(worldId, MAXIMUM_WORLD_ID_CHARACTERS)) {
      return { restored: false, status: 'invalid' }
    }
    const entry = this.entries.find(candidate => candidate.worldId === worldId)
    return entry
      ? { restored: true, action: cloneAction(entry.action) }
      : { restored: false, status: 'missing' }
  }

  private readSnapshot() {
    let raw: unknown
    try {
      raw = this.storage.getItem(this.storageKey)
    }
    catch {
      this.unhealthyReason = 'read-error'
      return []
    }
    if (raw === null) return []
    if (typeof raw !== 'string' || raw.length > MAXIMUM_PERSISTED_CHARACTERS) {
      this.unhealthyReason = 'corrupt-snapshot'
      return []
    }
    try {
      const parsed: unknown = JSON.parse(raw)
      if (!validSnapshot(parsed)) {
        this.unhealthyReason = 'corrupt-snapshot'
        return []
      }
      return parsed.entries.map(cloneEntry)
    }
    catch {
      this.unhealthyReason = 'corrupt-snapshot'
      return []
    }
  }

  private persist(entries: JournalEntry[]) {
    if (this.unhealthyReason) return false
    const snapshot: PersistedSnapshot = {
      schema: SNAPSHOT_SCHEMA,
      version: SNAPSHOT_VERSION,
      entries: entries.map(cloneEntry),
    }
    try {
      const serialized = JSON.stringify(snapshot)
      if (serialized.length > MAXIMUM_PERSISTED_CHARACTERS) {
        this.unhealthyReason = 'write-error'
        return false
      }
      this.storage.setItem(this.storageKey, serialized)
      return true
    }
    catch {
      this.unhealthyReason = 'write-error'
      return false
    }
  }
}

function validSnapshot(value: unknown): value is PersistedSnapshot {
  if (!exactRecord(value, ['schema', 'version', 'entries'])
      || value.schema !== SNAPSHOT_SCHEMA
      || value.version !== SNAPSHOT_VERSION
      || !Array.isArray(value.entries)
      || value.entries.length > PENDING_WORLD_ACTION_JOURNAL_CAPACITY) return false

  const worldIds = new Set<string>()
  const actionIds = new Set<string>()
  for (const entry of value.entries) {
    if (!exactRecord(entry, ['worldId', 'action'])
        || !validBoundedText(entry.worldId, MAXIMUM_WORLD_ID_CHARACTERS)
        || !validAction(entry.action)
        || worldIds.has(entry.worldId)
        || actionIds.has(entry.action.actionId)) return false
    worldIds.add(entry.worldId)
    actionIds.add(entry.action.actionId)
  }
  return true
}

function validAction(value: unknown): value is PendingWorldAction {
  const baseKeys = ['actionId', 'expectedRevision', 'room', 'position', 'activity']
  if (!exactRecord(value, baseKeys)
      && !exactRecord(value, [...baseKeys, 'interactionTarget'])) return false
  if (!validBoundedText(value.actionId, MAXIMUM_ACTION_ID_CHARACTERS)
      || !Number.isSafeInteger(value.expectedRevision)
      || (value.expectedRevision as number) < 0
      || !validBoundedText(value.room, MAXIMUM_ROOM_CHARACTERS)
      || !validBoundedText(value.activity, MAXIMUM_ACTIVITY_CHARACTERS)
      || (value.interactionTarget !== undefined
        && !validBoundedText(
          value.interactionTarget,
          MAXIMUM_INTERACTION_TARGET_CHARACTERS,
        ))
      || !exactRecord(value.position, ['x', 'y', 'z'])) return false
  return validCoordinate(value.position.x)
    && validCoordinate(value.position.y)
    && validCoordinate(value.position.z)
}

function exactRecord(
  value: unknown,
  expectedKeys: readonly string[],
): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false
  const keys = Object.keys(value)
  return keys.length === expectedKeys.length
    && expectedKeys.every(key => Object.prototype.hasOwnProperty.call(value, key))
}

function validBoundedText(value: unknown, maximumCharacters: number): value is string {
  return typeof value === 'string'
    && value.trim().length > 0
    && value.length <= maximumCharacters
}

function validCoordinate(value: unknown): value is number {
  return typeof value === 'number'
    && Number.isFinite(value)
    && Math.abs(value) <= MAXIMUM_WORLD_COORDINATE
}

function equalAction(left: PendingWorldAction, right: PendingWorldAction) {
  return left.actionId === right.actionId
    && left.expectedRevision === right.expectedRevision
    && left.room === right.room
    && left.position.x === right.position.x
    && left.position.y === right.position.y
    && left.position.z === right.position.z
    && left.activity === right.activity
    && left.interactionTarget === right.interactionTarget
}

function cloneAction(action: PendingWorldAction): PendingWorldAction {
  const clone: PendingWorldAction = {
    actionId: action.actionId,
    expectedRevision: action.expectedRevision,
    room: action.room,
    position: { ...action.position },
    activity: action.activity,
  }
  if (action.interactionTarget !== undefined) {
    clone.interactionTarget = action.interactionTarget
  }
  return clone
}

function cloneEntry(entry: JournalEntry): JournalEntry {
  return { worldId: entry.worldId, action: cloneAction(entry.action) }
}
