import { describe, expect, it } from 'vitest'
import { worldSnapshotEvent } from './world-snapshot-admission'

describe('world snapshot admission', () => {
  it('wraps a matching snapshot in a world-scoped event', () => {
    expect(worldSnapshotEvent({ worldId: { value: 'gahyeon-home' }, revision: 7 },
      'gahyeon-home')).toEqual({
      event: 'world.state.restored',
      data: {
        scope: { type: 'WORLD', id: 'gahyeon-home' },
        payload: { worldId: 'gahyeon-home', revision: 7 },
      },
    })
  })

  it('accepts the current plain-string WorldId JSON shape', () => {
    expect(worldSnapshotEvent({ worldId: 'gahyeon-home', revision: 7 }, 'gahyeon-home'))
      .toMatchObject({ event: 'world.state.restored' })
  })

  it('fails closed for malformed or foreign snapshots', () => {
    expect(worldSnapshotEvent({ worldId: { value: 'other-world' } }, 'gahyeon-home'))
      .toBeUndefined()
    expect(worldSnapshotEvent({ revision: 7 }, 'gahyeon-home')).toBeUndefined()
    expect(worldSnapshotEvent([], 'gahyeon-home')).toBeUndefined()
  })
})
