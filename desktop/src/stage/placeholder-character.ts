import {
  CapsuleGeometry,
  Group,
  Mesh,
  MeshStandardMaterial,
  SphereGeometry,
} from 'three'
import type { CharacterRenderer } from './character-renderer'
import type { StageState } from './stage-state'

export class PlaceholderCharacterRenderer implements CharacterRenderer {
  readonly object = new Group()
  private readonly materials: MeshStandardMaterial[]
  private elapsed = 0

  constructor() {
    const bodyMaterial = new MeshStandardMaterial({ color: '#aaa0ba', roughness: 0.76 })
    const headMaterial = new MeshStandardMaterial({ color: '#d9c7c1', roughness: 0.82 })
    this.materials = [bodyMaterial, headMaterial]

    const body = new Mesh(new CapsuleGeometry(0.42, 1.05, 8, 18), bodyMaterial)
    body.position.y = 1.08
    body.castShadow = true
    const head = new Mesh(new SphereGeometry(0.42, 28, 20), headMaterial)
    head.position.y = 2.08
    head.castShadow = true
    this.object.add(body, head)
  }

  update(state: StageState, deltaSeconds: number) {
    this.elapsed += deltaSeconds
    const idleAmount = state.activity === 'idle' ? 1 : 0.35
    this.object.position.y = Math.sin(this.elapsed * 1.4) * 0.018 * idleAmount
    const happy = state.expression === 'happy' ? state.expressionIntensity : 0
    this.materials[0].color.set(happy > 0 ? '#c3a7c9' : '#aaa0ba')
  }

  dispose() {
    this.object.traverse((object) => {
      if (!(object instanceof Mesh)) return
      object.geometry.dispose()
    })
    this.materials.forEach(material => material.dispose())
  }
}
