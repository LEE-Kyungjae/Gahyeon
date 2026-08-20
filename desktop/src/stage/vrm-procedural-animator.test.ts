import { describe, expect, it } from 'vitest'
import { presentationPose, proceduralPose } from './vrm-procedural-animator'

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

  it('overlays semantic gestures without discarding the current activity pose', () => {
    const wave = presentationPose('conversation', 'small_wave', 0.25)
    expect(wave.rightUpperArm).toEqual([-0.75, 0, 0.9])
    expect(wave.chest).toBeDefined()

    const nod = presentationPose('conversation', 'nod', 0.25)
    expect(Math.abs(nod.head?.[0] ?? 0)).toBeGreaterThan(0)
    expect(nod.chest).toBeDefined()
  })
})
