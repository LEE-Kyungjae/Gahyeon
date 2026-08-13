import { describe, expect, it } from 'vitest'
import { trimTimeline } from './bounded-timeline'

describe('trimTimeline', () => {
  it('retains the welcome entry and newest completed history inside a hard bound', () => {
    const entries = [
      { id: 'welcome' },
      ...Array.from({ length: 600 }, (_, index) => ({ id: `message-${index}` })),
    ]
    trimTimeline(entries, 500)
    expect(entries).toHaveLength(500)
    expect(entries[0].id).toBe('welcome')
    expect(entries[1].id).toBe('message-101')
    expect(entries.at(-1)?.id).toBe('message-599')
  })

  it('prefers a locally-owned pending response over older completed entries', () => {
    const entries = [
      { id: 'welcome' },
      { id: 'pending', requestId: 'active' },
      { id: 'old-complete' },
      { id: 'new-complete' },
    ]
    trimTimeline(entries, 3, new Set(['active']))
    expect(entries.map(entry => entry.id)).toEqual(['welcome', 'pending', 'new-complete'])
  })

  it('still enforces the hard bound under excessive protected traffic', () => {
    const entries: Array<{ id: string, requestId?: string }> = [
      { id: 'welcome' },
      ...Array.from({ length: 10 }, (_, index) => ({
        id: `pending-${index}`, requestId: `request-${index}`,
      })),
    ]
    trimTimeline(entries, 4, new Set(entries.flatMap(entry => entry.requestId ?? [])))
    expect(entries).toHaveLength(4)
    expect(entries[0].id).toBe('welcome')
  })

  it('rejects an unsafe bound', () => {
    expect(() => trimTimeline([], 0)).toThrow()
  })
})
