export type DesktopLatencyMetric =
  | 'vad_to_listening_state'
  | 'vad_end_to_stt_final'
  | 'barge_in_audio_stop'
  | 'request_to_first_delta'
  | 'request_to_first_audio'

export interface LatencySummary {
  sampleCount: number
  totalCount: number
  budgetMs?: number
  budgetViolations: number
  p50: number
  p95: number
  p99: number
  max: number
}

export class LatencyMetrics {
  private readonly samples = new Map<DesktopLatencyMetric, number[]>()
  private readonly totals = new Map<DesktopLatencyMetric, number>()
  private readonly violations = new Map<DesktopLatencyMetric, number>()

  private static readonly budgets: Partial<Record<DesktopLatencyMetric, number>> = {
    vad_to_listening_state: 100,
    vad_end_to_stt_final: 3_000,
    barge_in_audio_stop: 150,
  }

  constructor(private readonly capacity = 256) {
    if (!Number.isInteger(capacity) || capacity < 1) throw new Error('capacity must be positive')
  }

  record(metric: DesktopLatencyMetric, milliseconds: number) {
    if (!Number.isFinite(milliseconds) || milliseconds < 0) return false
    const values = this.samples.get(metric) ?? []
    values.push(milliseconds)
    if (values.length > this.capacity) values.splice(0, values.length - this.capacity)
    this.samples.set(metric, values)
    this.totals.set(metric, (this.totals.get(metric) ?? 0) + 1)
    const budget = LatencyMetrics.budgets[metric]
    if (budget !== undefined && milliseconds > budget) {
      this.violations.set(metric, (this.violations.get(metric) ?? 0) + 1)
    }
    return true
  }

  snapshot(): Partial<Record<DesktopLatencyMetric, LatencySummary>> {
    const result: Partial<Record<DesktopLatencyMetric, LatencySummary>> = {}
    for (const [metric, samples] of this.samples) {
      const sorted = [...samples].sort((left, right) => left - right)
      const budget = LatencyMetrics.budgets[metric]
      result[metric] = {
        sampleCount: sorted.length,
        totalCount: this.totals.get(metric) ?? sorted.length,
        ...(budget === undefined ? {} : { budgetMs: budget }),
        budgetViolations: this.violations.get(metric) ?? 0,
        p50: percentile(sorted, 0.50),
        p95: percentile(sorted, 0.95),
        p99: percentile(sorted, 0.99),
        max: sorted.at(-1) ?? 0,
      }
    }
    return result
  }
}

function percentile(sorted: number[], proportion: number) {
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(sorted.length * proportion) - 1))
  return Math.round((sorted[index] ?? 0) * 100) / 100
}
