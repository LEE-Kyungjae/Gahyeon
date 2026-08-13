export interface VoiceActivityConfig {
  startThreshold: number
  stopThreshold: number
  attackMs: number
  releaseMs: number
}

export type VoiceActivityEvent = 'none' | 'started' | 'ended' | 'invalid'

const defaults: VoiceActivityConfig = {
  startThreshold: 0.04,
  stopThreshold: 0.02,
  attackMs: 30,
  releaseMs: 350,
}

/** Desktop reference for the same local, model-independent gate used by RuntimeCore. */
export class VoiceActivityDetector {
  private active = false
  private candidateSince?: number
  private lastObservedAt?: number

  constructor(private readonly config: VoiceActivityConfig = defaults) {
    if (!Number.isFinite(config.startThreshold)
      || !Number.isFinite(config.stopThreshold)
      || config.startThreshold <= config.stopThreshold
      || config.stopThreshold < 0
      || config.startThreshold > 1
      || config.attackMs < 0
      || config.releaseMs < 0) {
      throw new Error('invalid voice activity configuration')
    }
  }

  observe(level: number, nowMs: number): VoiceActivityEvent {
    if (!Number.isFinite(level) || level < 0 || level > 1
      || !Number.isFinite(nowMs)
      || (this.lastObservedAt !== undefined && nowMs < this.lastObservedAt)) {
      return 'invalid'
    }
    this.lastObservedAt = nowMs
    const candidate = this.active
      ? level <= this.config.stopThreshold
      : level >= this.config.startThreshold
    if (!candidate) {
      this.candidateSince = undefined
      return 'none'
    }
    this.candidateSince ??= nowMs
    const required = this.active ? this.config.releaseMs : this.config.attackMs
    if (nowMs - this.candidateSince < required) return 'none'
    this.active = !this.active
    this.candidateSince = undefined
    return this.active ? 'started' : 'ended'
  }

  reset() {
    this.active = false
    this.candidateSince = undefined
    this.lastObservedAt = undefined
  }

  get isActive() {
    return this.active
  }
}
