import type {
  WorldActionCompletionRequest,
  WorldActionCompletionResponse,
} from '../gahyeon-api'

export const WORLD_ACTION_ACK_OUTBOX_CAPACITY = 32
export const WORLD_ACTION_ACK_OUTBOX_STORAGE_KEY = 'gahyeon.world-action-ack-outbox.v1'

const SNAPSHOT_SCHEMA = 'gahyeon.world-action-ack-outbox'
const SNAPSHOT_VERSION = 1
const MAXIMUM_PERSISTED_CHARACTERS = 64 * 1024
const INITIAL_RETRY_MILLIS = 250
const MAXIMUM_RETRY_MILLIS = 5_000
const MAXIMUM_WORLD_ID_CHARACTERS = 120
const MAXIMUM_ACTION_ID_CHARACTERS = 80
const MAXIMUM_INSTALLATION_ID_CHARACTERS = 200
const MAXIMUM_WORLD_COORDINATE = 1_000_000

export interface WorldActionAckStorage {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem?(key: string): void
}

export interface WorldActionAckCommand {
  worldId: string
  request: WorldActionCompletionRequest
}

export interface WorldActionAckEntry {
  command: WorldActionAckCommand
  attempts: number
  nextAttemptAt: number
}

export interface WorldActionAckClaim extends WorldActionAckEntry {
  token: number
  actionId: string
}

export type WorldActionAckAttemptOutcome =
  | WorldActionCompletionResponse['result']
  | 'NETWORK_FAILURE'

export type WorldActionAckEnqueueResult =
  | { accepted: true, status: 'accepted' | 'duplicate' }
  | { accepted: false, status: 'invalid' | 'conflict' | 'full' | 'storage-error' }

export type WorldActionAckClaimResult =
  | { status: 'claimed', claim: WorldActionAckClaim }
  | { status: 'none' | 'busy' | 'invalid-time' | 'storage-error' }

export type WorldActionAckFinishResult =
  | { finished: true, status: 'removed' | 'retained' }
  | { finished: false, status: 'invalid-claim' | 'invalid-outcome' | 'storage-error' }

export type WorldActionAckReconcileResult =
  | { removed: true, status: 'removed' }
  | { removed: false, status: 'missing' | 'invalid' | 'storage-error' }

interface PersistedSnapshot {
  schema: typeof SNAPSHOT_SCHEMA
  version: typeof SNAPSHOT_VERSION
  entries: WorldActionAckEntry[]
}

/**
 * Durable, bounded queue for renderer-completed world actions.
 *
 * A claim advances and persists its retry deadline before it is exposed to the
 * caller. This makes a renderer crash after the network send recover as a
 * delayed retry instead of a hot loop. The in-memory token permits only one
 * request at a time and rejects late completions after authoritative SSE
 * reconciliation.
 */
export class WorldActionAckOutbox {
  private entries: WorldActionAckEntry[]
  private activeClaim: { token: number, actionId: string } | undefined
  private nextClaimToken = 1
  private durabilityHealthy = true

  constructor(
    private readonly storage: WorldActionAckStorage,
    private readonly storageKey = WORLD_ACTION_ACK_OUTBOX_STORAGE_KEY,
  ) {
    this.entries = this.restore()
  }

  enqueue(command: WorldActionAckCommand, now: number): WorldActionAckEnqueueResult {
    if (!validCommand(command) || !validTimestamp(now)) {
      return { accepted: false, status: 'invalid' }
    }
    if (!this.durabilityHealthy) return { accepted: false, status: 'storage-error' }
    const existing = this.entries.find(entry => actionId(entry) === command.request.actionId)
    if (existing) {
      return equalCommand(existing.command, command)
        ? { accepted: true, status: 'duplicate' }
        : { accepted: false, status: 'conflict' }
    }
    if (this.entries.length >= WORLD_ACTION_ACK_OUTBOX_CAPACITY) {
      return { accepted: false, status: 'full' }
    }

    const candidate = [...this.entries, {
      command: cloneCommand(command),
      attempts: 0,
      nextAttemptAt: now,
    }]
    if (!this.persist(candidate)) return { accepted: false, status: 'storage-error' }
    this.entries = candidate
    return { accepted: true, status: 'accepted' }
  }

  /** Returns the first insertion-ordered due entry without reserving it. */
  due(now: number): WorldActionAckEntry | undefined {
    if (!this.durabilityHealthy || !validTimestamp(now) || this.activeClaim) return undefined
    const entry = this.entries.find(candidate => candidate.nextAttemptAt <= now)
    return entry ? cloneEntry(entry) : undefined
  }

  /** Persists attempt/retry state, then grants the sole in-flight claim. */
  claimDue(now: number): WorldActionAckClaimResult {
    if (!validTimestamp(now)) return { status: 'invalid-time' }
    if (!this.durabilityHealthy) return { status: 'storage-error' }
    if (this.activeClaim) return { status: 'busy' }
    const index = this.entries.findIndex(candidate => candidate.nextAttemptAt <= now)
    if (index < 0) return { status: 'none' }

    const current = this.entries[index]
    const attempts = current.attempts < Number.MAX_SAFE_INTEGER
      ? current.attempts + 1
      : Number.MAX_SAFE_INTEGER
    const nextAttemptAt = safeAdd(now, retryDelay(attempts))
    const updated: WorldActionAckEntry = {
      command: cloneCommand(current.command),
      attempts,
      nextAttemptAt,
    }
    const candidate = this.entries.map((entry, candidateIndex) =>
      candidateIndex === index ? updated : entry)
    if (!this.persist(candidate)) return { status: 'storage-error' }

    this.entries = candidate
    const token = this.nextClaimToken
    this.nextClaimToken = this.nextClaimToken < Number.MAX_SAFE_INTEGER
      ? this.nextClaimToken + 1
      : 1
    this.activeClaim = { token, actionId: actionId(updated) }
    return {
      status: 'claimed',
      claim: {
        ...cloneEntry(updated),
        token,
        actionId: actionId(updated),
      },
    }
  }

  /**
   * Completes an in-flight attempt. Only COMMITTED and DUPLICATE are response
   * terminals; all rejection and transport outcomes remain queued.
   */
  finish(
    claim: Pick<WorldActionAckClaim, 'token' | 'actionId'>,
    outcome: WorldActionAckAttemptOutcome,
  ): WorldActionAckFinishResult {
    if (!this.matchesActiveClaim(claim)) {
      return { finished: false, status: 'invalid-claim' }
    }
    if (!isAttemptOutcome(outcome)) {
      this.activeClaim = undefined
      return { finished: false, status: 'invalid-outcome' }
    }

    if (outcome !== 'COMMITTED' && outcome !== 'DUPLICATE') {
      this.activeClaim = undefined
      return { finished: true, status: 'retained' }
    }

    const candidate = this.entries.filter(entry => actionId(entry) !== claim.actionId)
    if (!this.persist(candidate)) {
      // The already-persisted retry deadline remains authoritative. Releasing
      // the local fence allows a later idempotent retry to recover safely.
      this.activeClaim = undefined
      return { finished: false, status: 'storage-error' }
    }
    this.entries = candidate
    this.activeClaim = undefined
    return { finished: true, status: 'removed' }
  }

  /** Removes an entry after a durable authoritative character.action.result. */
  reconcileActionResult(actionIdValue: string): WorldActionAckReconcileResult {
    if (!validBoundedText(actionIdValue, MAXIMUM_ACTION_ID_CHARACTERS)) {
      return { removed: false, status: 'invalid' }
    }
    if (!this.durabilityHealthy) return { removed: false, status: 'storage-error' }
    if (!this.entries.some(entry => actionId(entry) === actionIdValue)) {
      return { removed: false, status: 'missing' }
    }
    const candidate = this.entries.filter(entry => actionId(entry) !== actionIdValue)
    if (!this.persist(candidate)) return { removed: false, status: 'storage-error' }
    this.entries = candidate
    if (this.activeClaim?.actionId === actionIdValue) this.activeClaim = undefined
    return { removed: true, status: 'removed' }
  }

  /** Clears only the in-memory fence; the persisted retry deadline remains authoritative. */
  releaseClaim(actionIdValue: string) {
    if (this.activeClaim?.actionId !== actionIdValue) return false
    this.activeClaim = undefined
    return true
  }

  size() {
    return this.entries.length
  }

  has(actionIdValue: string) {
    return this.entries.some(entry => actionId(entry) === actionIdValue)
  }

  snapshot() {
    return this.entries.map(cloneEntry)
  }

  activeClaimActionId() {
    return this.activeClaim?.actionId
  }

  isDurabilityHealthy() {
    return this.durabilityHealthy
  }

  private matchesActiveClaim(claim: Pick<WorldActionAckClaim, 'token' | 'actionId'>) {
    return this.activeClaim?.token === claim.token
      && this.activeClaim.actionId === claim.actionId
  }

  private persist(entries: WorldActionAckEntry[]) {
    // A failed initial read means valid durable state may still exist. Never
    // overwrite that unknown state from an empty in-memory recovery.
    if (!this.durabilityHealthy) return false
    const snapshot: PersistedSnapshot = {
      schema: SNAPSHOT_SCHEMA,
      version: SNAPSHOT_VERSION,
      entries: entries.map(cloneEntry),
    }
    try {
      const serialized = JSON.stringify(snapshot)
      if (serialized.length > MAXIMUM_PERSISTED_CHARACTERS) {
        this.durabilityHealthy = false
        return false
      }
      this.storage.setItem(this.storageKey, serialized)
      return true
    }
    catch {
      this.durabilityHealthy = false
      return false
    }
  }

  private restore() {
    let raw: string | null
    try {
      raw = this.storage.getItem(this.storageKey)
    }
    catch {
      this.durabilityHealthy = false
      return []
    }
    if (raw === null) return []
    if (typeof raw !== 'string' || raw.length > MAXIMUM_PERSISTED_CHARACTERS) {
      this.durabilityHealthy = false
      return []
    }
    try {
      const parsed: unknown = JSON.parse(raw)
      if (!validSnapshot(parsed)) {
        this.durabilityHealthy = false
        return []
      }
      return parsed.entries.map(cloneEntry)
    }
    catch {
      this.durabilityHealthy = false
      return []
    }
  }
}

function validSnapshot(value: unknown): value is PersistedSnapshot {
  if (!exactRecord(value, ['schema', 'version', 'entries'])) return false
  if (value.schema !== SNAPSHOT_SCHEMA || value.version !== SNAPSHOT_VERSION
      || !Array.isArray(value.entries)
      || value.entries.length > WORLD_ACTION_ACK_OUTBOX_CAPACITY) return false

  const actionIds = new Set<string>()
  for (const candidate of value.entries) {
    if (!exactRecord(candidate, ['command', 'attempts', 'nextAttemptAt'])
        || !validCommand(candidate.command)
        || !validNonNegativeInteger(candidate.attempts)
        || !validTimestamp(candidate.nextAttemptAt)) return false
    const id = candidate.command.request.actionId
    if (actionIds.has(id)) return false
    actionIds.add(id)
  }
  return true
}

function validCommand(value: unknown): value is WorldActionAckCommand {
  if (!exactRecord(value, ['worldId', 'request'])
      || !validBoundedText(value.worldId, MAXIMUM_WORLD_ID_CHARACTERS)
      || !exactRecord(value.request, [
        'installationId', 'actionId', 'expectedRevision', 'finalPosition',
      ])) return false
  const request = value.request
  if (!validBoundedText(request.installationId, MAXIMUM_INSTALLATION_ID_CHARACTERS)
      || !validBoundedText(request.actionId, MAXIMUM_ACTION_ID_CHARACTERS)
      || !validNonNegativeInteger(request.expectedRevision)
      || !exactRecord(request.finalPosition, ['x', 'y', 'z'])) return false
  const position = request.finalPosition
  return validCoordinate(position.x)
    && validCoordinate(position.y)
    && validCoordinate(position.z)
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

function validNonNegativeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) >= 0
}

function validTimestamp(value: unknown): value is number {
  return validNonNegativeInteger(value)
}

function validCoordinate(value: unknown): value is number {
  return typeof value === 'number'
    && Number.isFinite(value)
    && Math.abs(value) <= MAXIMUM_WORLD_COORDINATE
}

function equalCommand(left: WorldActionAckCommand, right: WorldActionAckCommand) {
  return left.worldId === right.worldId
    && left.request.installationId === right.request.installationId
    && left.request.actionId === right.request.actionId
    && left.request.expectedRevision === right.request.expectedRevision
    && left.request.finalPosition.x === right.request.finalPosition.x
    && left.request.finalPosition.y === right.request.finalPosition.y
    && left.request.finalPosition.z === right.request.finalPosition.z
}

function cloneCommand(command: WorldActionAckCommand): WorldActionAckCommand {
  return {
    worldId: command.worldId,
    request: {
      installationId: command.request.installationId,
      actionId: command.request.actionId,
      expectedRevision: command.request.expectedRevision,
      finalPosition: { ...command.request.finalPosition },
    },
  }
}

function cloneEntry(entry: WorldActionAckEntry): WorldActionAckEntry {
  return {
    command: cloneCommand(entry.command),
    attempts: entry.attempts,
    nextAttemptAt: entry.nextAttemptAt,
  }
}

function actionId(entry: WorldActionAckEntry) {
  return entry.command.request.actionId
}

function retryDelay(attempts: number) {
  const exponent = Math.min(Math.max(attempts - 1, 0), 5)
  return Math.min(INITIAL_RETRY_MILLIS * (2 ** exponent), MAXIMUM_RETRY_MILLIS)
}

function safeAdd(left: number, right: number) {
  return left > Number.MAX_SAFE_INTEGER - right
    ? Number.MAX_SAFE_INTEGER
    : left + right
}

function isAttemptOutcome(value: unknown): value is WorldActionAckAttemptOutcome {
  return value === 'COMMITTED'
    || value === 'DUPLICATE'
    || value === 'STALE'
    || value === 'CONFLICT'
    || value === 'INVALID'
    || value === 'RECORDED_FAILURE'
    || value === 'NETWORK_FAILURE'
}
