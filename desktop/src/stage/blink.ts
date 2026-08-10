const CYCLE_SECONDS = 4.6
const CLOSE_SECONDS = 0.075
const HOLD_SECONDS = 0.035
const OPEN_SECONDS = 0.09

export function blinkWeight(elapsedSeconds: number): number {
  const phase = ((elapsedSeconds % CYCLE_SECONDS) + CYCLE_SECONDS) % CYCLE_SECONDS
  if (phase < CLOSE_SECONDS) return phase / CLOSE_SECONDS
  if (phase < CLOSE_SECONDS + HOLD_SECONDS) return 1
  if (phase < CLOSE_SECONDS + HOLD_SECONDS + OPEN_SECONDS) {
    return 1 - (phase - CLOSE_SECONDS - HOLD_SECONDS) / OPEN_SECONDS
  }
  return 0
}
