export type IntentLayer = 'reflex' | 'behavior' | 'cognition'
export type IntentChannel = 'phase' | 'attention' | 'gesture' | 'posture' | 'expression' | 'speech'

export interface CharacterIntent<T = unknown> {
  id: string
  layer: IntentLayer
  channel: IntentChannel
  /** Omit for ambient intents that must survive conversation generation changes. */
  generation?: number
  priority: number
  createdAtMs: number
  expiresAfterMs?: number
  value: T
}

export interface ResolvedIntents {
  generation: number
  channels: Partial<Record<IntentChannel, CharacterIntent>>
}

/**
 * Engine-independent reference for the Unreal layer arbiter. It deliberately has
 * no timers or rendering dependencies, so delay/order/cancellation behavior is
 * deterministic and portable to C++ tests.
 */
export class IntentRuntime {
  private generation = 0
  private readonly intents = new Map<string, CharacterIntent>()

  currentGeneration() {
    return this.generation
  }

  beginGeneration() {
    this.generation += 1
    return this.generation
  }

  publish<T>(intent: CharacterIntent<T>) {
    if (!intent.id.trim()) throw new Error('intent id is required')
    if (!Number.isFinite(intent.priority)) throw new Error('intent priority must be finite')
    if (!Number.isFinite(intent.createdAtMs)) throw new Error('intent timestamp must be finite')
    if (intent.expiresAfterMs !== undefined && intent.expiresAfterMs < 0) {
      throw new Error('intent expiry must be non-negative')
    }
    if (intent.generation !== undefined && intent.generation < this.generation) return false
    this.intents.set(intent.id, intent as CharacterIntent)
    return true
  }

  resolve(nowMs: number): ResolvedIntents {
    const channels: ResolvedIntents['channels'] = {}
    for (const intent of this.intents.values()) {
      if (intent.generation !== undefined && intent.generation !== this.generation) continue
      if (expired(intent, nowMs)) continue
      const selected = channels[intent.channel]
      if (!selected || wins(intent, selected)) channels[intent.channel] = intent
    }
    return { generation: this.generation, channels }
  }

  compact(nowMs: number) {
    for (const [id, intent] of this.intents) {
      if (intent.generation !== undefined && intent.generation < this.generation || expired(intent, nowMs)) {
        this.intents.delete(id)
      }
    }
  }
}

function expired(intent: CharacterIntent, nowMs: number) {
  return intent.expiresAfterMs !== undefined
    && nowMs >= intent.createdAtMs + intent.expiresAfterMs
}

function wins(candidate: CharacterIntent, selected: CharacterIntent) {
  if (candidate.priority !== selected.priority) return candidate.priority > selected.priority
  if (candidate.createdAtMs !== selected.createdAtMs) return candidate.createdAtMs > selected.createdAtMs
  return candidate.id > selected.id
}
