import type { GahyeonDesktopEvent } from '../gahyeon-api'

/** Converts only the requested world's snapshot into the normal scoped reducer contract. */
export function worldSnapshotEvent(
  snapshot: unknown,
  worldId: string,
): GahyeonDesktopEvent | undefined {
  const payload = record(snapshot)
  const rawWorldId = payload?.worldId
  const snapshotWorld = typeof rawWorldId === 'string'
    ? rawWorldId
    : record(rawWorldId)?.value
  if (!payload || snapshotWorld !== worldId) return undefined
  return {
    event: 'world.state.restored',
    data: {
      scope: { type: 'WORLD', id: worldId },
      payload: { ...payload, worldId: snapshotWorld },
    },
  }
}

function record(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined
}
