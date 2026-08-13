import { describe, expect, it } from 'vitest'
import { VoiceActivityDetector } from './voice-activity-detector'

describe('VoiceActivityDetector', () => {
  it('rejects a noise spike and applies attack/release hysteresis', () => {
    const vad = new VoiceActivityDetector({
      startThreshold: 0.05,
      stopThreshold: 0.02,
      attackMs: 30,
      releaseMs: 350,
    })

    expect(vad.observe(0.06, 0)).toBe('none')
    expect(vad.observe(0.01, 20)).toBe('none')
    expect(vad.observe(0.06, 100)).toBe('none')
    expect(vad.observe(0.07, 130)).toBe('started')
    expect(vad.isActive).toBe(true)
    expect(vad.observe(0.01, 200)).toBe('none')
    expect(vad.observe(0.01, 549)).toBe('none')
    expect(vad.observe(0.01, 550)).toBe('ended')
    expect(vad.isActive).toBe(false)
  })

  it('rejects invalid frames without corrupting its monotonic clock', () => {
    const vad = new VoiceActivityDetector()
    expect(vad.observe(2, 100)).toBe('invalid')
    expect(vad.observe(0, 100)).toBe('none')
    expect(vad.observe(0, 99)).toBe('invalid')
  })
})
