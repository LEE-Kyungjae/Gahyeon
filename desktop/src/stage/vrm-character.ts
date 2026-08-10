import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import { Group } from 'three'
import { VRM, VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm'
import type { CharacterRenderer } from './character-renderer'
import type { StageState } from './stage-state'

export class VrmCharacterRenderer implements CharacterRenderer {
  readonly object = new Group()
  private constructor(private readonly vrm: VRM) {
    VRMUtils.rotateVRM0(vrm)
    this.object.add(vrm.scene)
  }

  static async load(url: string) {
    const loader = new GLTFLoader()
    loader.register(parser => new VRMLoaderPlugin(parser))
    const gltf = await loader.loadAsync(url)
    if (!gltf.userData.vrm) throw new Error('VRM 모델을 읽을 수 없습니다.')
    return new VrmCharacterRenderer(gltf.userData.vrm as VRM)
  }

  update(state: StageState, deltaSeconds: number) {
    const expression = this.vrm.expressionManager
    if (expression) {
      expression.setValue('happy', state.expression === 'happy' ? state.expressionIntensity : 0)
      expression.setValue('relaxed', state.expression === 'relaxed' ? state.expressionIntensity : 0)
      expression.setValue('aa', state.speaking ? 0.28 : 0)
      expression.update()
    }
    this.vrm.update(deltaSeconds)
  }

  dispose() {
    VRMUtils.deepDispose(this.vrm.scene)
    this.object.remove(this.vrm.scene)
  }
}
