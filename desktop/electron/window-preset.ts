import type { BrowserWindowConstructorOptions } from 'electron'

export type DesktopWindowPreset = {
  name: 'standard' | 'character'
  options: BrowserWindowConstructorOptions
  alwaysOnTop: boolean
  clickThrough: boolean
}

function enabled(value: string | undefined, name: string): boolean {
  if (value === undefined || value === '' || value === 'false' || value === '0') return false
  if (value === 'true' || value === '1') return true
  throw new Error(`${name} must be true/false or 1/0`)
}

export function resolveWindowPreset(environment: NodeJS.ProcessEnv): DesktopWindowPreset {
  const preset = environment.GAHYEON_DESKTOP_WINDOW_PRESET ?? 'standard'
  if (preset !== 'standard' && preset !== 'character') {
    throw new Error('GAHYEON_DESKTOP_WINDOW_PRESET must be standard or character')
  }
  const character = preset === 'character'
  const clickThrough = enabled(environment.GAHYEON_DESKTOP_CLICK_THROUGH, 'GAHYEON_DESKTOP_CLICK_THROUGH')
  if (clickThrough && !character) throw new Error('click-through requires the character window preset')
  return {
    name: preset,
    options: {
      width: character ? 640 : 1180,
      height: character ? 700 : 760,
      minWidth: character ? 300 : 820,
      minHeight: character ? 420 : 560,
      backgroundColor: character ? '#00000000' : '#101218',
      transparent: character,
      frame: !character,
      hasShadow: !character,
      resizable: true,
      title: 'Gahyeon',
    },
    alwaysOnTop: character && enabled(environment.GAHYEON_DESKTOP_ALWAYS_ON_TOP, 'GAHYEON_DESKTOP_ALWAYS_ON_TOP'),
    clickThrough,
  }
}
