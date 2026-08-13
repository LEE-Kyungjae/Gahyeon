import { describe, expect, it } from 'vitest'
import { SpeechRequestRegistry } from './speech-request-registry'

describe('SpeechRequestRegistry', () => {
  it('cancels all speech requests for one renderer without touching another', () => {
    const registry = new SpeechRequestRegistry()
    const first = registry.begin(1)
    const second = registry.begin(1)
    const other = registry.begin(2)

    expect(registry.cancel(1)).toBe(2)
    expect(first.signal.aborted).toBe(true)
    expect(second.signal.aborted).toBe(true)
    expect(other.signal.aborted).toBe(false)
    expect(registry.activeCount(1)).toBe(0)
    expect(registry.activeCount(2)).toBe(1)
  })

  it('completion is idempotent and removes only its own request', () => {
    const registry = new SpeechRequestRegistry()
    const first = registry.begin(1)
    const second = registry.begin(1)
    first.complete()
    first.complete()
    expect(registry.activeCount(1)).toBe(1)
    second.complete()
    expect(registry.activeCount(1)).toBe(0)
  })
})
