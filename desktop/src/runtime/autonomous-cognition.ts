import type { VoiceExpression } from '../gahyeon-api'

export interface AutonomousCognition {
  characterId: string
  utterance: string
  voiceProfile: string
  expression: VoiceExpression
  facialExpression: string
  gazeTarget: string
  gesture: string
  resumePreviousActivity: boolean
}

const styles = new Set([
  'natural', 'warm', 'gentle', 'bright', 'surprised', 'concerned', 'serious',
  'playful', 'fake_cute', 'sarcastic', 'sleepy', 'whisper', 'excited',
  'annoyed', 'sad', 'suppressed_laugh',
])

export function parseAutonomousCognition(data: unknown): AutonomousCognition | undefined {
  if (!record(data) || data.spoken !== true) return undefined
  if (!slug(data.characterId) || !slug(data.voiceProfile)) return undefined
  if (typeof data.utterance !== 'string') return undefined
  const utterance = data.utterance.trim()
  if (!utterance || utterance.length > 2_000) return undefined
  if (!record(data.expressionPlan)) return undefined
  const style = data.expressionPlan.voiceStyle
  const intensity = data.expressionPlan.intensity
  const intent = data.expressionPlan.communicativeIntent
  const facialExpression = data.expressionPlan.facialExpression
  const gazeTarget = data.expressionPlan.gazeTarget
  const gesture = data.expressionPlan.gesture
  const resumePreviousActivity = data.expressionPlan.resumePreviousActivity
  if (typeof style !== 'string' || !styles.has(style)) return undefined
  if (typeof intensity !== 'number' || !Number.isFinite(intensity)
      || intensity < 0 || intensity > 1) return undefined
  if (typeof intent !== 'string' || !intent.trim() || intent.length > 80) return undefined
  if (!boundedName(facialExpression) || !boundedName(gazeTarget) || !boundedName(gesture)) return undefined
  if (typeof resumePreviousActivity !== 'boolean') return undefined
  return {
    characterId: data.characterId,
    utterance,
    voiceProfile: data.voiceProfile,
    expression: { style, intensity, communicativeIntent: intent.trim() },
    facialExpression,
    gazeTarget,
    gesture,
    resumePreviousActivity,
  }
}

function record(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

function slug(value: unknown): value is string {
  return typeof value === 'string'
    && value.length <= 100
    && /^[a-z0-9][a-z0-9._-]*$/.test(value)
}

function boundedName(value: unknown): value is string {
  return typeof value === 'string'
    && value.length <= 80
    && /^[a-z0-9][a-z0-9._-]*$/.test(value)
}
