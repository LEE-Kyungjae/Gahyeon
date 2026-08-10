export type AnimationActivity =
  | 'idle'
  | 'walk'
  | 'sit'
  | 'read'
  | 'sleep'
  | 'work'
  | 'look_outside'
  | 'relax'
  | 'attention'
  | 'conversation'

const supported = new Set<AnimationActivity>([
  'idle',
  'walk',
  'sit',
  'read',
  'sleep',
  'work',
  'look_outside',
  'relax',
  'attention',
  'conversation',
])

export function animationActivity(value: string): AnimationActivity {
  const normalized = value.trim().toLowerCase().replaceAll('-', '_') as AnimationActivity
  return supported.has(normalized) ? normalized : 'idle'
}

export function validateAnimationManifest(value: unknown): Partial<Record<AnimationActivity, string>> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('VRMA manifest는 activity-to-URL JSON 객체여야 합니다.')
  }
  const result: Partial<Record<AnimationActivity, string>> = {}
  for (const [rawActivity, rawUrl] of Object.entries(value as Record<string, unknown>)) {
    const activity = animationActivity(rawActivity)
    if (activity !== rawActivity.trim().toLowerCase().replaceAll('-', '_')) {
      throw new Error(`지원하지 않는 VRMA activity: ${rawActivity}`)
    }
    if (typeof rawUrl !== 'string' || !rawUrl.trim()) {
      throw new Error(`${rawActivity} VRMA URL이 비어 있습니다.`)
    }
    result[activity] = rawUrl.trim()
  }
  return result
}
