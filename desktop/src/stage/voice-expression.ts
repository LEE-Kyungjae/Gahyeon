import type { VoiceExpression } from '../gahyeon-api'

export type StageExpression = 'neutral' | 'happy' | 'angry' | 'sad' | 'surprised' | 'relaxed'

export function stageExpressionForVoiceStyle(style: string): StageExpression {
  switch (style.trim().toLowerCase()) {
    case 'bright':
    case 'joy':
      return 'happy'
    case 'annoyed':
    case 'anger':
      return 'angry'
    case 'sad':
      return 'sad'
    case 'surprised':
    case 'surprise':
      return 'surprised'
    case 'calm':
    case 'soft':
      return 'relaxed'
    default:
      return 'neutral'
  }
}

export function stageExpressionForSemantic(face: string, voiceStyle: string): StageExpression {
  switch (face.trim().toLowerCase()) {
    case 'happy':
    case 'joy':
    case 'smile':
    case 'playful':
      return 'happy'
    case 'angry':
    case 'annoyed':
    case 'irritated':
      return 'angry'
    case 'sad':
    case 'concerned':
    case 'worried':
      return 'sad'
    case 'surprised':
    case 'surprise':
      return 'surprised'
    case 'relaxed':
    case 'calm':
    case 'sleepy':
    case 'attentive':
      return 'relaxed'
    default:
      return stageExpressionForVoiceStyle(voiceStyle)
  }
}

export function stageExpressionPayload(expression: VoiceExpression) {
  return {
    expression: stageExpressionForVoiceStyle(expression.style),
    intensity: Math.max(0, Math.min(1, expression.intensity)),
  }
}
