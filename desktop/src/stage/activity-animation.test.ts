import { describe, expect, it } from 'vitest'
import { animationActivity, validateAnimationManifest } from './activity-animation'

describe('activity animation contract', () => {
  it('normalizes Core activity names and falls back safely', () => {
    expect(animationActivity('LOOK-OUTSIDE')).toBe('look_outside')
    expect(animationActivity('unknown')).toBe('idle')
  })

  it('accepts only supported activity URL mappings', () => {
    expect(validateAnimationManifest({ idle: '/idle.vrma', work: '/work.vrma' }))
      .toEqual({ idle: '/idle.vrma', work: '/work.vrma' })
    expect(() => validateAnimationManifest({ dance: '/dance.vrma' }))
      .toThrow('지원하지 않는 VRMA activity')
  })
})
