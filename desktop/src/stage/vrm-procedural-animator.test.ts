import { describe, expect, it } from 'vitest'
import { proceduralPose } from './vrm-procedural-animator'

describe('procedural VRM activity poses', () => {
  it('produces a cyclic walk pose with opposing limbs', () => {
    const pose = proceduralPose('walk', Math.PI / 13.6)
    expect(pose.leftUpperArm?.[0]).toBeCloseTo(-pose.rightUpperArm![0], 5)
    expect(pose.leftUpperLeg?.[0]).toBeCloseTo(-pose.rightUpperLeg![0], 5)
  })

  it('produces seated leg articulation for work and reading', () => {
    expect(proceduralPose('work', 0).leftUpperLeg?.[0]).toBeLessThan(-1)
    expect(proceduralPose('read', 0).leftLowerLeg?.[0]).toBeGreaterThan(1)
  })
})
