import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import { Group } from 'three'
import { VRM, VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm'
import type { CharacterRenderer } from './character-renderer'
import type { StageState } from './stage-state'
import { VrmAnimationController } from './vrm-animation-controller'

export class VrmCharacterRenderer implements CharacterRenderer {
  readonly object = new Group()
  readonly animationWarnings: string[] = []
  private readonly animations: VrmAnimationController
  private constructor(private readonly vrm: VRM) {
    VRMUtils.rotateVRM0(vrm)
    this.animations = new VrmAnimationController(vrm)
    this.object.add(vrm.scene)
  }

  static async load(url: string, animationManifestUrl?: string) {
    const loader = new GLTFLoader()
    loader.register(parser => new VRMLoaderPlugin(parser))
    const gltf = await loader.loadAsync(url)
    if (!gltf.userData.vrm) throw new Error('VRM 모델을 읽을 수 없습니다.')
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
    this.animations.update(state.activity, deltaSeconds)
    const expression = this.vrm.expressionManager
    if (expression) {
      expression.setValue('happy', state.expression === 'happy' ? state.expressionIntensity : 0)
      expression.setValue('relaxed', state.expression === 'relaxed' ? state.expressionIntensity : 0)
      expression.setValue('aa', state.speaking ? Math.max(0.08, state.speechAmplitude) : 0)
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
