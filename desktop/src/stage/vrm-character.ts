import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import { Group } from 'three'
import { VRM, VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm'
import type { CharacterRenderer } from './character-renderer'
import type { StageState } from './stage-state'
import { VrmAnimationController } from './vrm-animation-controller'
import { blinkWeight } from './blink'
import { GahyeonClientError } from '../client-error'

export class VrmCharacterRenderer implements CharacterRenderer {
  readonly object = new Group()
  readonly animationWarnings: string[] = []
  private readonly animations: VrmAnimationController
  private readonly expressionWeights = new Map<string, number>()
  private elapsed = 1.1
  private constructor(private readonly vrm: VRM) {
    VRMUtils.rotateVRM0(vrm)
    this.animations = new VrmAnimationController(vrm)
    this.object.add(vrm.scene)
  }

  static async load(url: string, animationManifestUrl?: string) {
    const loader = new GLTFLoader()
    loader.register(parser => new VRMLoaderPlugin(parser))
    const gltf = await loader.loadAsync(url)
    if (!gltf.userData.vrm) throw new GahyeonClientError('vrmInvalid')
    const renderer = new VrmCharacterRenderer(gltf.userData.vrm as VRM)
    if (animationManifestUrl) {
      try {
        renderer.animationWarnings.push(...await renderer.animations.loadManifest(animationManifestUrl))
      }
      catch (error) {
        renderer.animationWarnings.push(error instanceof Error ? error.message : String(error))
      }
    }
    return renderer
  }

  update(state: StageState, deltaSeconds: number) {
    this.elapsed += deltaSeconds
    this.animations.update(state.activity, deltaSeconds, state.gesture)
    applyGaze(this.vrm, state.gazeTarget, deltaSeconds)
    const expression = this.vrm.expressionManager
    if (expression) {
      const blend = 1 - Math.exp(-Math.max(0, deltaSeconds) * 8)
      for (const name of ['happy', 'angry', 'sad', 'surprised', 'relaxed']) {
        const current = this.expressionWeights.get(name) ?? 0
        const target = state.expression === name ? state.expressionIntensity : 0
        const next = current + (target - current) * blend
        this.expressionWeights.set(name, next)
        expression.setValue(name, next)
      }
      expression.setValue('aa', state.speaking ? Math.max(0.08, state.speechAmplitude) : 0)
      expression.setValue('blink', blinkWeight(this.elapsed))
      expression.update()
    }
    this.vrm.update(deltaSeconds)
  }

  dispose() {
    this.animations.dispose()
    VRMUtils.deepDispose(this.vrm.scene)
    this.object.remove(this.vrm.scene)
  }
}

function applyGaze(vrm: VRM, target: string, deltaSeconds: number) {
  const head = vrm.humanoid.getNormalizedBoneNode('head')
  if (!head) return
  const yaw = target === 'window' ? 0.22
    : target === 'left' ? 0.16
      : target === 'right' ? -0.16 : 0
  const blend = 1 - Math.exp(-Math.max(0, deltaSeconds) * 4.5)
  head.rotation.y += (yaw - head.rotation.y) * blend
}
