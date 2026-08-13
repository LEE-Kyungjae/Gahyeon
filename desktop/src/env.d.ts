import type { GahyeonDesktopBridge } from './gahyeon-api'
import type { LatencySummary, DesktopLatencyMetric } from './runtime/latency-metrics'

declare global {
  interface Window {
    gahyeon: GahyeonDesktopBridge
    gahyeonRuntimeDiagnostics: {
      latencySnapshot(): Partial<Record<DesktopLatencyMetric, LatencySummary>>
    }
  }
}

export {}
