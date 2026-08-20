import { describe, expect, it } from 'vitest'
import { resolveNativeGlass } from './native-glass.js'

describe('native glass platform adapter', () => {
  it('uses Desktop Acrylic for the Windows character surface', () => {
    expect(resolveNativeGlass('win32', {}, true)).toEqual({
      enabled: true,
      engine: 'windows-acrylic',
      options: { backgroundMaterial: 'acrylic' },
    })
  })

  it('uses under-window vibrancy for the macOS character surface', () => {
    expect(resolveNativeGlass('darwin', {}, true)).toEqual({
      enabled: true,
      engine: 'macos-vibrancy',
      options: { vibrancy: 'under-window', visualEffectState: 'active' },
    })
  })

  it('keeps standard, disabled, and unsupported windows unchanged', () => {
    expect(resolveNativeGlass('win32', {}, false).enabled).toBe(false)
    expect(resolveNativeGlass('darwin', { GAHYEON_DESKTOP_NATIVE_GLASS: 'off' }, true).enabled).toBe(false)
    expect(resolveNativeGlass('linux', {}, true).enabled).toBe(false)
  })

  it('fails closed for an unknown mode', () => {
    expect(() => resolveNativeGlass(
      'win32',
      { GAHYEON_DESKTOP_NATIVE_GLASS: 'maybe' },
      true,
    )).toThrow()
  })
})
