import { describe, expect, it } from 'vitest'
import { parseAutonomousCognition } from './autonomous-cognition'

describe('autonomous cognition admission', () => {
  it('preserves one bounded expression plan', () => {
    expect(parseAutonomousCognition({
      spoken: true,
      characterId: 'gahyeon',
      utterance: '밖에 비 오던데 우산 가져갔어?',
      voiceProfile: 'gahyeon.assistant',
      expressionPlan: {
        voiceStyle: 'concerned', intensity: 0.62, communicativeIntent: 'check_in',
        facialExpression: 'concerned', gazeTarget: 'user', gesture: 'small_wave',
        resumePreviousActivity: true,
      },
    })).toMatchObject({
      characterId: 'gahyeon',
      expression: { style: 'concerned', intensity: 0.62 },
      facialExpression: 'concerned', gazeTarget: 'user', gesture: 'small_wave',
      resumePreviousActivity: true,
    })
  })

  it('rejects silence, unknown style, and oversized utterance', () => {
    expect(parseAutonomousCognition({ spoken: false })).toBeUndefined()
    expect(parseAutonomousCognition({
      spoken: true, characterId: 'gahyeon', utterance: '안녕', voiceProfile: 'gahyeon.assistant',
      expressionPlan: { voiceStyle: 'provider jailbreak', intensity: 1, communicativeIntent: 'greet', facialExpression: 'happy', gazeTarget: 'user', gesture: 'wave', resumePreviousActivity: true },
    })).toBeUndefined()
    expect(parseAutonomousCognition({
      spoken: true, characterId: 'gahyeon', utterance: '가'.repeat(2_001),
      voiceProfile: 'gahyeon.assistant',
      expressionPlan: { voiceStyle: 'natural', intensity: 1, communicativeIntent: 'greet', facialExpression: 'happy', gazeTarget: 'user', gesture: 'wave', resumePreviousActivity: true },
    })).toBeUndefined()
    expect(parseAutonomousCognition({
      spoken: true, characterId: 'gahyeon', utterance: '안녕', voiceProfile: 'gahyeon.assistant',
      expressionPlan: { voiceStyle: 'natural', intensity: 0.3, communicativeIntent: 'greet', facialExpression: 'happy', gazeTarget: 'user', gesture: 'wave' },
    })).toBeUndefined()
  })
})
