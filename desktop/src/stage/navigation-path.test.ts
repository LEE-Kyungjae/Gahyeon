import { describe, expect, it } from 'vitest'
import { buildNavigationPath } from './navigation-path'

describe('world navigation paths', () => {
  it('routes between rooms through stable doorways', () => {
    expect(buildNavigationPath('bedroom', 'workspace', { x: 7, y: 0, z: -2 }))
      .toEqual([
        { x: 2.7, y: 0, z: -2.3 },
        { x: 5.1, y: 0, z: -2.5 },
        { x: 7, y: 0, z: -2 },
      ])
  })

  it('uses a direct path inside the same room', () => {
    expect(buildNavigationPath('living_room', 'living_room', { x: -2, y: 0, z: -5 }))
      .toEqual([{ x: -2, y: 0, z: -5 }])
  })
})
