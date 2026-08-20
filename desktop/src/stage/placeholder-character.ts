import {
  BoxGeometry,
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
  private readonly mouth: Mesh
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
    const mouthMaterial = new MeshStandardMaterial({ color: '#4a343d', roughness: 0.9 })
    this.materials.push(mouthMaterial)
    this.mouth = new Mesh(new BoxGeometry(0.16, 0.025, 0.018), mouthMaterial)
    this.mouth.position.set(0, 1.98, 0.407)
    this.object.add(body, head, this.mouth)
  }

  update(state: StageState, deltaSeconds: number) {
    this.elapsed += deltaSeconds
    const idleAmount = state.activity === 'idle' ? 1 : 0.35
    this.object.position.y = Math.sin(this.elapsed * 1.4) * 0.018 * idleAmount
    const expressionColor = {
      happy: '#c3a7c9',
      angry: '#c58f91',
      sad: '#8fa3c5',
      surprised: '#d4b58f',
      relaxed: '#9fbbae',
    }[state.expression] ?? '#aaa0ba'
    this.materials[0].color.set(state.expressionIntensity > 0 ? expressionColor : '#aaa0ba')
    this.mouth.scale.y = 1 + state.speechAmplitude * 9
  }

  dispose() {
    this.object.traverse((object) => {
      if (!(object instanceof Mesh)) return
      object.geometry.dispose()
    })
    this.materials.forEach(material => material.dispose())
  }
}
