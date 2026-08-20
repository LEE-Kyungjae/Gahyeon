import { describe, expect, it } from 'vitest'
import { cameraFraming } from './three-stage'

describe('desktop character camera framing', () => {
  it('moves progressively closer from full body to bust to face', () => {
    const full = cameraFraming('full-body')
    const bust = cameraFraming('bust')
    const face = cameraFraming('face')
    expect(full.offset.z).toBeGreaterThan(bust.offset.z)
    expect(bust.offset.z).toBeGreaterThan(face.offset.z)
    expect(face.target.y).toBeGreaterThan(bust.target.y)
    expect(bust.target.y).toBeGreaterThan(full.target.y)
  })
})
