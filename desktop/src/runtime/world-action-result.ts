const terminalResults = new Set([
  'committed',
  'duplicate',
  'recorded_failure',
  'conflict',
])

/** Returns an action id only when the authoritative SSE outcome is terminal. */
export function terminalActionResultId(data: unknown) {
  const envelope = record(data)
  const payload = record(envelope?.payload)
  if (!payload || typeof payload.actionId !== 'string'
      || typeof payload.result !== 'string'
      || !terminalResults.has(payload.result.toLowerCase())) return ''
  return payload.actionId
}

function record(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined
}
