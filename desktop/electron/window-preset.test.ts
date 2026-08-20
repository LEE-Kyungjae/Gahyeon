import { describe, expect, it } from 'vitest'
import { resolveWindowPreset } from './window-preset.js'

describe('desktop window preset', () => {
  it('preserves the existing standard window by default', () => {
    const preset = resolveWindowPreset({})
    expect(preset.options).toMatchObject({ width: 1180, height: 760, frame: true, transparent: false })
    expect(preset.alwaysOnTop).toBe(false)
    expect(preset.clickThrough).toBe(false)
  })

  it('creates a cross-platform transparent character surface', () => {
    const preset = resolveWindowPreset({
      GAHYEON_DESKTOP_WINDOW_PRESET: 'character',
      GAHYEON_DESKTOP_ALWAYS_ON_TOP: 'true',
      GAHYEON_DESKTOP_CLICK_THROUGH: '1',
    })
    expect(preset.options).toMatchObject({
      width: 640,
      height: 700,
      minWidth: 300,
      minHeight: 420,
      frame: false,
      transparent: true,
      resizable: true,
    })
    expect(preset.alwaysOnTop).toBe(true)
    expect(preset.clickThrough).toBe(true)
  })

  it('fails closed for invalid or unsafe combinations', () => {
    expect(() => resolveWindowPreset({ GAHYEON_DESKTOP_WINDOW_PRESET: 'floating' })).toThrow()
    expect(() => resolveWindowPreset({ GAHYEON_DESKTOP_CLICK_THROUGH: 'true' })).toThrow()
    expect(() => resolveWindowPreset({
      GAHYEON_DESKTOP_WINDOW_PRESET: 'character', GAHYEON_DESKTOP_ALWAYS_ON_TOP: 'sometimes',
    })).toThrow()
  })
})
