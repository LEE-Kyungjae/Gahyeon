import { describe, expect, it } from 'vitest'
import { stageExpressionForSemantic, stageExpressionForVoiceStyle, stageExpressionPayload } from './voice-expression'

describe('voice expression presentation', () => {
  it('maps cognition semantics to expressions the renderer supports', () => {
    expect(stageExpressionForSemantic('concerned', 'natural')).toBe('sad')
    expect(stageExpressionForSemantic('attentive', 'natural')).toBe('relaxed')
    expect(stageExpressionForSemantic('neutral', 'bright')).toBe('happy')
  })
  it.each([
    ['bright', 'happy'],
    ['joy', 'happy'],
    ['annoyed', 'angry'],
    ['anger', 'angry'],
    ['sad', 'sad'],
    ['surprised', 'surprised'],
    ['calm', 'relaxed'],
    ['unknown', 'neutral'],
  ])('maps %s voice style to %s face expression', (voice, face) => {
    expect(stageExpressionForVoiceStyle(voice)).toBe(face)
  })

  it('clamps untrusted intensity before it reaches a renderer', () => {
    expect(stageExpressionPayload({
      style: 'bright',
      intensity: 1.8,
      communicativeIntent: 'test',
    })).toEqual({ expression: 'happy', intensity: 1 })
  })
})
