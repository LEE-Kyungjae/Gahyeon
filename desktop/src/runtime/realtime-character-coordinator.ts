import { IntentRuntime } from './intent-runtime'

/**
 * Engine-independent reference coordinator. Unreal C++ should reproduce these
 * transitions while keeping its frame-level animation implementation local.
 */
export class RealtimeCharacterCoordinator {
  readonly intents = new IntentRuntime()
  private thinkingGeneration?: number
  private thinkingDeadlineMs?: number

  constructor(nowMs = 0, private readonly cognitionTimeoutMs = 10_000) {
    if (!Number.isFinite(cognitionTimeoutMs) || cognitionTimeoutMs <= 0)
      throw new Error('cognition timeout must be positive')
    this.intents.publish({
      id: 'ambient.posture',
      layer: 'behavior',
      channel: 'posture',
      priority: 1,
      createdAtMs: nowMs,
      value: 'ambient_alive',
    })
  }

  voiceStarted(nowMs: number) {
    this.clearThinkingDeadline()
    const generation = this.intents.beginGeneration()
    this.intents.publish({
      id: 'conversation.phase',
      layer: 'reflex',
      channel: 'phase',
      generation,
      priority: 100,
      createdAtMs: nowMs,
      value: 'listening',
    })
    this.intents.publish({
      id: `attention.user.${generation}`,
      layer: 'reflex',
      channel: 'attention',
      generation,
      priority: 100,
      createdAtMs: nowMs,
      expiresAfterMs: 750,
      value: 'user',
    })
    return generation
  }

  voiceEnded(generation: number, nowMs: number) {
    const accepted = this.intents.publish({
      id: 'conversation.phase',
      layer: 'behavior',
      channel: 'phase',
      generation,
      priority: 60,
      createdAtMs: nowMs,
      value: 'thinking',
    })
    if (accepted) {
      this.thinkingGeneration = generation
      this.thinkingDeadlineMs = nowMs + this.cognitionTimeoutMs
    }
    return accepted
  }

  speechStarted(generation: number, nowMs: number, utteranceId: string) {
    const phaseAccepted = this.intents.publish({
      id: 'conversation.phase',
      layer: 'cognition',
      channel: 'phase',
      generation,
      priority: 70,
      createdAtMs: nowMs,
      value: 'speaking',
    })
    if (!phaseAccepted) return false
    this.clearThinkingDeadline()
    return this.intents.publish({
      id: 'conversation.speech',
      layer: 'cognition',
      channel: 'speech',
      generation,
      priority: 70,
      createdAtMs: nowMs,
      value: utteranceId,
    })
  }

  speechEnded(generation: number, nowMs: number) {
    const speechAccepted = this.intents.publish({
      id: 'conversation.speech',
      layer: 'behavior',
      channel: 'speech',
      generation,
      priority: 10,
      createdAtMs: nowMs,
      value: '',
    })
    if (!speechAccepted) return false
    const phaseAccepted = this.intents.publish({
      id: 'conversation.phase',
      layer: 'behavior',
      channel: 'phase',
      generation,
      priority: 10,
      createdAtMs: nowMs,
      value: 'idle',
    })
    if (phaseAccepted) this.clearThinkingDeadline()
    return phaseAccepted
  }

  advance(nowMs: number) {
    if (this.thinkingGeneration === undefined || this.thinkingDeadlineMs === undefined
      || nowMs < this.thinkingDeadlineMs) return undefined
    if (this.thinkingGeneration !== this.intents.currentGeneration()) {
      this.clearThinkingDeadline()
      return undefined
    }
    const generation = this.intents.beginGeneration()
    this.clearThinkingDeadline()
    this.speechEnded(generation, nowMs)
    return generation
  }

  private clearThinkingDeadline() {
    this.thinkingGeneration = undefined
    this.thinkingDeadlineMs = undefined
  }
}
