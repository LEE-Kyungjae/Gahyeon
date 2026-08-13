import type { GahyeonDesktopEvent } from '../gahyeon-api'

const worldStateEvents = new Set([
  'avatar.expression',
  'character.moved',
  'behavior.activity.changed',
  'world.state.changed',
  'world.state.restored',
  'world.transition.target',
  'character.action.result',
])

/** Renderer-side fail-closed guard for durable state owned by a specific world. */
export function isEventVisibleToWorld(event: GahyeonDesktopEvent, worldId: string) {
  const envelope = record(event.data)
  const scope = record(envelope?.scope)
  if (scope?.type !== 'WORLD') return !worldStateEvents.has(event.event)
  const payload = record(envelope?.payload)
  if (!payload || scope.id !== worldId) return false
  return payload.worldId === undefined || payload.worldId === worldId
}

function record(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined
}
