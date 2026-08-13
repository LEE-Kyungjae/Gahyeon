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
  | 'listening'
  | 'thinking'
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
  'listening',
  'thinking',
  'conversation',
])

export function animationActivity(value: string): AnimationActivity {
  const normalized = value.trim().toLowerCase().replaceAll('-', '_') as AnimationActivity
  return supported.has(normalized) ? normalized : 'idle'
}

export function validateAnimationManifest(value: unknown): Partial<Record<AnimationActivity, string>> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new GahyeonClientError('vrmaManifest', 'invalid JSON shape')
  }
  const result: Partial<Record<AnimationActivity, string>> = {}
  for (const [rawActivity, rawUrl] of Object.entries(value as Record<string, unknown>)) {
    const activity = animationActivity(rawActivity)
    if (activity !== rawActivity.trim().toLowerCase().replaceAll('-', '_')) {
      throw new GahyeonClientError('vrmaManifest', `unsupported activity ${rawActivity}`)
    }
    if (typeof rawUrl !== 'string' || !rawUrl.trim()) {
      throw new GahyeonClientError('vrmaManifest', `missing URL for ${rawActivity}`)
    }
    result[activity] = rawUrl.trim()
  }
  return result
}
import { GahyeonClientError } from '../client-error'
