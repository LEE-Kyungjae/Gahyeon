import { describe, expect, it } from 'vitest'
import { blinkWeight } from './blink'

describe('blinkWeight', () => {
  it('closes, holds, opens, and stays open for the rest of the cycle', () => {
    expect(blinkWeight(0)).toBe(0)
    expect(blinkWeight(0.075)).toBe(1)
    expect(blinkWeight(0.09)).toBe(1)
    expect(blinkWeight(0.2)).toBeCloseTo(0)
    expect(blinkWeight(2)).toBe(0)
    expect(blinkWeight(4.6)).toBe(0)
  })
})
