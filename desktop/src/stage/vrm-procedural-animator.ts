import { Euler, Quaternion } from 'three'
import type { VRM, VRMHumanBoneName } from '@pixiv/three-vrm'
import { animationActivity, type AnimationActivity } from './activity-animation'

type Rotation = readonly [number, number, number]
type Pose = Partial<Record<VRMHumanBoneName, Rotation>>

const trackedBones: VRMHumanBoneName[] = [
  'hips', 'spine', 'chest', 'head',
  'leftUpperArm', 'leftLowerArm', 'rightUpperArm', 'rightLowerArm',
  'leftUpperLeg', 'leftLowerLeg', 'rightUpperLeg', 'rightLowerLeg',
]

export class VrmProceduralAnimator {
  private readonly rest = new Map<VRMHumanBoneName, Quaternion>()
  private elapsed = 0

  constructor(private readonly vrm: VRM) {
    for (const boneName of trackedBones) {
      const bone = vrm.humanoid.getNormalizedBoneNode(boneName)
      if (bone) this.rest.set(boneName, bone.quaternion.clone())
    }
  }

  update(rawActivity: string, deltaSeconds: number) {
    this.elapsed += deltaSeconds
    const activity = animationActivity(rawActivity)
    const pose = proceduralPose(activity, this.elapsed)
    const blend = 1 - Math.exp(-deltaSeconds * 7.5)
    const offset = new Quaternion()

    for (const [boneName, rest] of this.rest) {
      const bone = this.vrm.humanoid.getNormalizedBoneNode(boneName)
      if (!bone) continue
      const rotation = pose[boneName] ?? [0, 0, 0]
      offset.setFromEuler(new Euler(rotation[0], rotation[1], rotation[2], 'XYZ'))
      bone.quaternion.slerp(rest.clone().multiply(offset), blend)
    }
  }
}

export function proceduralPose(activity: AnimationActivity, elapsed: number): Pose {
  const breath = Math.sin(elapsed * 1.5)
  const stride = Math.sin(elapsed * 6.8)
  switch (activity) {
    case 'walk':
      return {
        chest: [0.02, 0, stride * 0.035],
        leftUpperArm: [stride * 0.55, 0, -0.08],
        rightUpperArm: [-stride * 0.55, 0, 0.08],
        leftUpperLeg: [-stride * 0.48, 0, 0],
        rightUpperLeg: [stride * 0.48, 0, 0],
        leftLowerLeg: [Math.max(0, stride) * 0.5, 0, 0],
        rightLowerLeg: [Math.max(0, -stride) * 0.5, 0, 0],
      }
    case 'sit':
      return seatedPose(0.05)
    case 'read':
      return {
        ...seatedPose(0.12),
        head: [0.14, 0, 0],
        leftUpperArm: [-0.45, 0, -0.32],
        rightUpperArm: [-0.45, 0, 0.32],
        leftLowerArm: [-1.05, 0, 0],
        rightLowerArm: [-1.05, 0, 0],
      }
    case 'sleep':
      return {
        spine: [0.08, 0, 0.2],
        chest: [0.02, 0, 0.14],
        head: [0.03, 0, 0.12],
        leftUpperArm: [0.2, 0, -0.35],
        rightUpperArm: [0.2, 0, 0.35],
        leftUpperLeg: [-0.15, 0, 0.08],
        rightUpperLeg: [0.12, 0, -0.08],
      }
    case 'work':
      return {
        ...seatedPose(0.08),
        head: [0.06, Math.sin(elapsed * 0.45) * 0.04, 0],
        leftUpperArm: [-0.72, 0, -0.16],
        rightUpperArm: [-0.72, 0, 0.16],
        leftLowerArm: [-0.7 + Math.max(0, breath) * 0.05, 0, 0],
        rightLowerArm: [-0.7 + Math.max(0, -breath) * 0.05, 0, 0],
      }
    case 'look_outside':
      return {
        spine: [0, Math.sin(elapsed * 0.2) * 0.06, 0],
        head: [-0.03, Math.sin(elapsed * 0.25) * 0.24, 0],
        leftUpperArm: [0.08, 0, -0.1],
        rightUpperArm: [0.08, 0, 0.1],
      }
    case 'relax':
      return {
        ...seatedPose(-0.08),
        spine: [-0.1, 0, 0],
        head: [-0.04, Math.sin(elapsed * 0.3) * 0.08, 0],
        leftUpperArm: [0.18, 0, -0.32],
        rightUpperArm: [0.18, 0, 0.32],
      }
    case 'attention':
      return {
        spine: [-0.02, 0, 0],
        head: [-0.05, 0, 0],
        leftUpperArm: [0, 0, -0.08],
        rightUpperArm: [0, 0, 0.08],
      }
    case 'listening':
      return {
        spine: [-0.035, 0, 0],
        chest: [-0.015, 0, 0],
        head: [-0.055 + Math.sin(elapsed * 1.1) * 0.012, 0.035, 0.025],
        leftUpperArm: [0, 0, -0.075],
        rightUpperArm: [0, 0, 0.075],
      }
    case 'thinking':
      return {
        spine: [0.015, 0, 0],
        chest: [0.01, 0, 0],
        head: [0.015, 0.16 + Math.sin(elapsed * 0.35) * 0.025, -0.035],
        leftUpperArm: [-0.08, 0, -0.1],
        rightUpperArm: [-0.22, 0, 0.22],
        rightLowerArm: [-0.82, 0, 0],
      }
    case 'conversation':
      return {
        chest: [breath * 0.012, 0, 0],
        head: [-0.03, Math.sin(elapsed * 0.65) * 0.045, 0],
        leftUpperArm: [0.03 + breath * 0.03, 0, -0.12],
        rightUpperArm: [0.03 - breath * 0.03, 0, 0.12],
      }
    case 'idle':
    default:
      return {
        spine: [breath * 0.008, 0, 0],
        chest: [breath * 0.014, 0, 0],
        head: [0, Math.sin(elapsed * 0.22) * 0.025, 0],
        leftUpperArm: [0, 0, -0.06],
        rightUpperArm: [0, 0, 0.06],
      }
  }
}

function seatedPose(spineX: number): Pose {
  return {
    spine: [spineX, 0, 0],
    leftUpperLeg: [-1.08, 0, 0.06],
    rightUpperLeg: [-1.08, 0, -0.06],
    leftLowerLeg: [1.25, 0, 0],
    rightLowerLeg: [1.25, 0, 0],
  }
}
