import { describe, expect, it } from 'vitest'
import {
  CONTROLS_GLASS_HEIGHT,
  CONTROLS_GLASS_WIDTH,
  controlsGlassBounds,
  roundedCapsuleShape,
} from './controls-glass-surface.js'

describe('controls glass surface geometry', () => {
  it('tracks the expanded controls above the bottom-right summary', () => {
    expect(controlsGlassBounds({ x: 100, y: 50, width: 640, height: 700 })).toEqual({
      x: 678,
      y: 558,
      width: CONTROLS_GLASS_WIDTH,
      height: CONTROLS_GLASS_HEIGHT,
    })
  })

  it('creates a bounded capsule region for Windows', () => {
    const shape = roundedCapsuleShape(CONTROLS_GLASS_WIDTH, CONTROLS_GLASS_HEIGHT)
    expect(shape).toHaveLength(CONTROLS_GLASS_HEIGHT)
    expect(shape[0]!.width).toBeLessThan(CONTROLS_GLASS_WIDTH)
    expect(shape[Math.floor(CONTROLS_GLASS_HEIGHT / 2)]).toEqual({
      x: 0, y: Math.floor(CONTROLS_GLASS_HEIGHT / 2), width: CONTROLS_GLASS_WIDTH, height: 1,
    })
    expect(shape.every(row => row.x >= 0 && row.width > 0 && row.x + row.width <= CONTROLS_GLASS_WIDTH)).toBe(true)
  })
})
