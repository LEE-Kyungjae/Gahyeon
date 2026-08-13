import { describe, expect, it } from 'vitest'
import { IntentRuntime } from './intent-runtime'

describe('IntentRuntime', () => {
  it('keeps reflex and behavior active while cognition has not returned', () => {
    const runtime = new IntentRuntime()
    runtime.publish({
      id: 'ambient', layer: 'behavior', channel: 'posture',
      priority: 10, createdAtMs: 0, value: 'breathing',
    })
    runtime.publish({
      id: 'vad', layer: 'reflex', channel: 'attention', generation: 0,
      priority: 100, createdAtMs: 20, expiresAfterMs: 500, value: 'user',
    })

    const state = runtime.resolve(300)
    expect(state.channels.posture?.value).toBe('breathing')
    expect(state.channels.attention?.value).toBe('user')
    expect(state.channels.speech).toBeUndefined()
  })

  it('rejects a stale cognition result after a new user turn', () => {
    const runtime = new IntentRuntime()
    const oldGeneration = runtime.currentGeneration()
    const currentGeneration = runtime.beginGeneration()

    expect(runtime.publish({
      id: 'late-llm', layer: 'cognition', channel: 'speech', generation: oldGeneration,
      priority: 50, createdAtMs: 5_000, value: 'stale response',
    })).toBe(false)
    expect(runtime.publish({
      id: 'listening', layer: 'reflex', channel: 'phase', generation: currentGeneration,
      priority: 100, createdAtMs: 5_001, value: 'listening',
    })).toBe(true)
    expect(runtime.resolve(5_002).channels.phase?.value).toBe('listening')
    expect(runtime.resolve(5_002).channels.speech).toBeUndefined()
  })

  it('lets a short reflex overlay expire back to behavior', () => {
    const runtime = new IntentRuntime()
    runtime.publish({
      id: 'ambient-look', layer: 'behavior', channel: 'attention',
      priority: 10, createdAtMs: 0, value: 'window',
    })
    runtime.publish({
      id: 'sound-reflex', layer: 'reflex', channel: 'attention', generation: 0,
      priority: 90, createdAtMs: 100, expiresAfterMs: 200, value: 'sound',
    })

    expect(runtime.resolve(250).channels.attention?.value).toBe('sound')
    expect(runtime.resolve(300).channels.attention?.value).toBe('window')
  })

  it('resolves equal-priority arrivals deterministically', () => {
    const runtime = new IntentRuntime()
    runtime.publish({
      id: 'older', layer: 'behavior', channel: 'gesture', generation: 0,
      priority: 20, createdAtMs: 100, value: 'first',
    })
    runtime.publish({
      id: 'newer', layer: 'behavior', channel: 'gesture', generation: 0,
      priority: 20, createdAtMs: 101, value: 'second',
    })
    expect(runtime.resolve(101).channels.gesture?.value).toBe('second')
  })

  it('keeps ambient life motion across conversation generations', () => {
    const runtime = new IntentRuntime()
    runtime.publish({
      id: 'breathing', layer: 'behavior', channel: 'posture',
      priority: 1, createdAtMs: 0, value: 'ambient-breathing',
    })

    runtime.beginGeneration()
    runtime.beginGeneration()

    expect(runtime.resolve(10_000).channels.posture?.value).toBe('ambient-breathing')
  })
})
