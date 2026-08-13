import { describe, expect, it } from 'vitest'
import { LatencyMetrics } from './latency-metrics'

describe('LatencyMetrics', () => {
  it('keeps a bounded ring and reports deterministic percentiles', () => {
    const metrics = new LatencyMetrics(4)
    for (const value of [1, 2, 3, 4, 100]) metrics.record('request_to_first_delta', value)

    expect(metrics.snapshot().request_to_first_delta).toEqual({
      sampleCount: 4, totalCount: 5, budgetViolations: 0,
      p50: 3, p95: 100, p99: 100, max: 100,
    })
  })

  it('accumulates acceptance-budget violations beyond the sample ring', () => {
    const metrics = new LatencyMetrics(2)
    metrics.record('vad_to_listening_state', 90)
    metrics.record('vad_to_listening_state', 101)
    metrics.record('vad_to_listening_state', 120)

    expect(metrics.snapshot().vad_to_listening_state).toMatchObject({
      sampleCount: 2, totalCount: 3, budgetMs: 100, budgetViolations: 2,
    })
  })

  it('rejects invalid durations', () => {
    const metrics = new LatencyMetrics()
    expect(metrics.record('vad_to_listening_state', -1)).toBe(false)
    expect(metrics.record('vad_to_listening_state', Number.NaN)).toBe(false)
    expect(metrics.snapshot()).toEqual({})
  })
})
