export interface TimelineEntry {
  id: string
  requestId?: string
}

/** Bounds renderer memory without truncating Core conversation history or persistent Memory. */
export function trimTimeline<T extends TimelineEntry>(
  entries: T[],
  maximumEntries: number,
  protectedRequestIds: ReadonlySet<string> = new Set(),
  pinnedIds: ReadonlySet<string> = new Set(['welcome']),
) {
  if (!Number.isSafeInteger(maximumEntries) || maximumEntries < 1) {
    throw new Error('maximum timeline entries must be a positive safe integer')
  }
  while (entries.length > maximumEntries) {
    let removable = entries.findIndex(entry =>
      !pinnedIds.has(entry.id)
      && (entry.requestId === undefined || !protectedRequestIds.has(entry.requestId)),
    )
    if (removable < 0) removable = entries.findIndex(entry => !pinnedIds.has(entry.id))
    if (removable < 0) removable = 0
    entries.splice(removable, 1)
  }
  return entries
}
