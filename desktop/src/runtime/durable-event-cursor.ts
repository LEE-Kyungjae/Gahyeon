import type { GahyeonDesktopEvent } from '../gahyeon-api'

/** Rejects duplicate, reordered and malformed durable SSE events before presentation side effects. */
export class DurableEventCursor {
  private cursor: number

  constructor(persisted: unknown) {
    this.cursor = safeCursor(persisted) ?? 0
  }

  current() {
    return this.cursor
  }

  prepare(event: Pick<GahyeonDesktopEvent, 'id'>): CursorAdmission | undefined {
    if (event.id === undefined) return { durable: false, cursor: this.cursor }
    const candidate = safeCursor(event.id)
    if (candidate === undefined || candidate <= this.cursor) return undefined
    return { durable: true, cursor: candidate }
  }

  /** Commit only after every synchronous presentation side effect has succeeded. */
  commit(admission: CursorAdmission) {
    if (!admission.durable) return false
    if (admission.cursor <= this.cursor) return false
    this.cursor = admission.cursor
    return true
  }
}

export interface CursorAdmission {
  durable: boolean
  cursor: number
}

function safeCursor(value: unknown) {
  if (typeof value === 'number') {
    return Number.isSafeInteger(value) && value >= 0 ? value : undefined
  }
  if (typeof value !== 'string' || !/^(0|[1-9]\d*)$/.test(value)) return undefined
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : undefined
}
