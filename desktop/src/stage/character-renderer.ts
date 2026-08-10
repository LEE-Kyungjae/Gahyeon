import type { Object3D } from 'three'
import type { StageState } from './stage-state'

export interface CharacterRenderer {
  readonly object: Object3D
  update(state: StageState, deltaSeconds: number): void
  dispose(): void
}
