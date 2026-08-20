import type { BrowserWindowConstructorOptions } from 'electron'

export type NativeGlassMode = 'off' | 'auto'

export type NativeGlassResolution = {
  enabled: boolean
  engine: 'none' | 'windows-acrylic' | 'macos-vibrancy'
  options: Pick<BrowserWindowConstructorOptions, 'backgroundMaterial' | 'vibrancy' | 'visualEffectState'>
}

function mode(value: string | undefined): NativeGlassMode {
  if (value === undefined || value === '' || value === 'auto' || value === 'true' || value === '1') return 'auto'
  if (value === 'off' || value === 'false' || value === '0') return 'off'
  throw new Error('GAHYEON_DESKTOP_NATIVE_GLASS must be auto/off, true/false, or 1/0')
}

export function resolveNativeGlass(
  platform: NodeJS.Platform,
  environment: NodeJS.ProcessEnv,
  characterWindow: boolean,
): NativeGlassResolution {
  if (!characterWindow || mode(environment.GAHYEON_DESKTOP_NATIVE_GLASS) === 'off') {
    return { enabled: false, engine: 'none', options: {} }
  }
  if (platform === 'win32') {
    return {
      enabled: true,
      engine: 'windows-acrylic',
      options: { backgroundMaterial: 'acrylic' },
    }
  }
  if (platform === 'darwin') {
    return {
      enabled: true,
      engine: 'macos-vibrancy',
      options: { vibrancy: 'under-window', visualEffectState: 'active' },
    }
  }
  return { enabled: false, engine: 'none', options: {} }
}
