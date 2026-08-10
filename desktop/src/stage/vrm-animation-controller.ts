import { AnimationAction, AnimationMixer, LoopRepeat } from 'three'
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js'
import type { VRM } from '@pixiv/three-vrm'
import {
  createVRMAnimationClip,
  VRMAnimationLoaderPlugin,
  type VRMAnimation,
} from '@pixiv/three-vrm-animation'
import { animationActivity, validateAnimationManifest, type AnimationActivity } from './activity-animation'
import { VrmProceduralAnimator } from './vrm-procedural-animator'

export class VrmAnimationController {
  private readonly mixer: AnimationMixer
  private readonly procedural: VrmProceduralAnimator
  private readonly actions = new Map<AnimationActivity, AnimationAction>()
  private activeActivity?: AnimationActivity
  private activeAction?: AnimationAction

  constructor(private readonly vrm: VRM) {
    this.mixer = new AnimationMixer(vrm.scene)
    this.procedural = new VrmProceduralAnimator(vrm)
  }

  async loadManifest(url: string) {
    const response = await fetch(url, { cache: 'no-store' })
    if (!response.ok) throw new Error(`VRMA manifest 응답 오류 (${response.status})`)
    const manifest = validateAnimationManifest(await response.json())
    const loader = new GLTFLoader()
    loader.register(parser => new VRMAnimationLoaderPlugin(parser))
    const failures: string[] = []

    await Promise.all(Object.entries(manifest).map(async ([rawActivity, animationUrl]) => {
      const activity = animationActivity(rawActivity)
      try {
        const gltf = await loader.loadAsync(animationUrl)
        const animation = (gltf.userData.vrmAnimations as VRMAnimation[] | undefined)?.[0]
        if (!animation) throw new Error('VRMC_vrm_animation clip이 없습니다.')
        const clip = createVRMAnimationClip(animation, this.vrm)
        clip.name = `gahyeon.${activity}`
        const action = this.mixer.clipAction(clip)
        action.setLoop(LoopRepeat, Infinity)
        this.actions.set(activity, action)
      }
      catch (error) {
        failures.push(`${activity}: ${error instanceof Error ? error.message : String(error)}`)
      }
    }))
    return failures
  }

  update(rawActivity: string, deltaSeconds: number) {
    const activity = animationActivity(rawActivity)
    if (activity !== this.activeActivity || (!this.activeAction && this.actions.has(activity))) {
      this.transition(activity)
    }
    this.mixer.update(deltaSeconds)
    if (!this.activeAction) this.procedural.update(activity, deltaSeconds)
  }

  dispose() {
    this.mixer.stopAllAction()
    this.mixer.uncacheRoot(this.vrm.scene)
    this.actions.clear()
  }

  private transition(activity: AnimationActivity) {
    const next = this.actions.get(activity)
    if (next === this.activeAction) {
      this.activeActivity = activity
      return
    }
    this.activeAction?.fadeOut(0.35)
    if (next) next.reset().setEffectiveTimeScale(1).setEffectiveWeight(1).fadeIn(0.35).play()
    this.activeAction = next
    this.activeActivity = activity
  }
}
