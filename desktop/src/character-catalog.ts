import rawCharacterManifest from './character-manifest.json'

export type DesktopCharacterId = string

export interface DesktopCharacterProfile {
  id: DesktopCharacterId
  displayName: string
  personaPrompt: string
  memoryNamespace: string
  voiceProfile: string
  expressionProfile: string
  autonomousEnabled: boolean
  mediaPath: string
  activityMediaPaths?: Readonly<Record<string, string>>
  unrealAssetPath: string
  productionReady: boolean
}

export const DESKTOP_CHARACTER_STORAGE_KEY = 'gahyeon.desktop.character.v1'

export interface DesktopCharacterManifest {
  schemaVersion: 1
  primaryCharacterId: DesktopCharacterId
  defaultCharacterId: DesktopCharacterId
  characters: readonly DesktopCharacterProfile[]
}

const CHARACTER_ID = /^[a-z0-9][a-z0-9._-]{0,63}$/
const PROFILE_ID = /^[a-z0-9][a-z0-9._-]{0,99}$/
const ACTIVITY_ID = /^[a-z0-9][a-z0-9_]{0,63}$/

function object(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`${field} must be an object`)
  }
  return value as Record<string, unknown>
}

function text(value: unknown, field: string, pattern?: RegExp): string {
  if (typeof value !== 'string' || value.trim() !== value || value.length === 0 || (pattern && !pattern.test(value))) {
    throw new Error(`${field} is invalid`)
  }
  return value
}

function bundledMediaPath(value: unknown, field: string): string {
  const path = text(value, field)
  if (!path.startsWith('./') || path.includes('..') || path.includes('\\')) throw new Error(`${field} must be a bundled relative path`)
  return path
}

function parseProfile(value: unknown, index: number): DesktopCharacterProfile {
  const entry = object(value, `characters[${index}]`)
  const activitiesValue = entry.activityMediaPaths
  let activityMediaPaths: Readonly<Record<string, string>> | undefined
  if (activitiesValue !== undefined) {
    const activities = object(activitiesValue, `characters[${index}].activityMediaPaths`)
    activityMediaPaths = Object.freeze(Object.fromEntries(Object.entries(activities).map(([activity, path]) => [
      text(activity, `characters[${index}].activityMediaPaths key`, ACTIVITY_ID),
      bundledMediaPath(path, `characters[${index}].activityMediaPaths.${activity}`),
    ])))
  }
  if (typeof entry.autonomousEnabled !== 'boolean') throw new Error(`characters[${index}].autonomousEnabled is invalid`)
  if (typeof entry.productionReady !== 'boolean') throw new Error(`characters[${index}].productionReady is invalid`)
  const unrealAssetPath = text(entry.unrealAssetPath, `characters[${index}].unrealAssetPath`)
  if (!unrealAssetPath.startsWith('/Game/')) throw new Error(`characters[${index}].unrealAssetPath is invalid`)
  return Object.freeze({
    id: text(entry.id, `characters[${index}].id`, CHARACTER_ID),
    displayName: text(entry.displayName, `characters[${index}].displayName`),
    personaPrompt: text(entry.personaPrompt, `characters[${index}].personaPrompt`),
    memoryNamespace: text(entry.memoryNamespace, `characters[${index}].memoryNamespace`),
    voiceProfile: text(entry.voiceProfile, `characters[${index}].voiceProfile`, PROFILE_ID),
    expressionProfile: text(entry.expressionProfile, `characters[${index}].expressionProfile`, PROFILE_ID),
    autonomousEnabled: entry.autonomousEnabled,
    mediaPath: bundledMediaPath(entry.mediaPath, `characters[${index}].mediaPath`),
    activityMediaPaths,
    unrealAssetPath,
    productionReady: entry.productionReady,
  })
}

export function parseDesktopCharacterManifest(value: unknown): DesktopCharacterManifest {
  const manifest = object(value, 'character manifest')
  if (manifest.schemaVersion !== 1) throw new Error('unsupported character manifest schema')
  if (!Array.isArray(manifest.characters) || manifest.characters.length === 0) throw new Error('characters must not be empty')
  const characters = Object.freeze(manifest.characters.map(parseProfile))
  const ids = new Set(characters.map(character => character.id))
  if (ids.size !== characters.length) throw new Error('character ids must be unique')
  const primaryCharacterId = text(manifest.primaryCharacterId, 'primaryCharacterId', CHARACTER_ID)
  const defaultCharacterId = text(manifest.defaultCharacterId, 'defaultCharacterId', CHARACTER_ID)
  if (!ids.has(primaryCharacterId)) throw new Error('primary character is not configured')
  if (!ids.has(defaultCharacterId)) throw new Error('default character is not configured')
  return Object.freeze({ schemaVersion: 1, primaryCharacterId, defaultCharacterId, characters })
}

export const desktopCharacterManifest = parseDesktopCharacterManifest(rawCharacterManifest)
export const primaryDesktopCharacterId = desktopCharacterManifest.primaryCharacterId
export const defaultDesktopCharacterId = desktopCharacterManifest.defaultCharacterId
export const desktopCharacters = desktopCharacterManifest.characters

export function isDesktopCharacterId(value: string | null): value is DesktopCharacterId {
  return desktopCharacters.some(character => character.id === value)
}

export function restoreDesktopCharacter(storage: Pick<Storage, 'getItem'>): DesktopCharacterId {
  const stored = storage.getItem(DESKTOP_CHARACTER_STORAGE_KEY)
  return isDesktopCharacterId(stored) ? stored : defaultDesktopCharacterId
}

export function desktopCharacter(id: DesktopCharacterId): DesktopCharacterProfile {
  return desktopCharacters.find(character => character.id === id)
    ?? desktopCharacters.find(character => character.id === defaultDesktopCharacterId)!
}

export function characterMediaUrl(
  profile: DesktopCharacterProfile,
  baseUrl: string,
  gahyeonOverride?: string,
): string {
  if (profile.id === 'gahyeon' && gahyeonOverride) return gahyeonOverride
  return new URL(profile.mediaPath, baseUrl).href
}

export function characterActivityMediaUrl(
  profile: DesktopCharacterProfile,
  baseUrl: string,
  activity: string,
  gahyeonOverride?: string,
): string {
  if (profile.id === 'gahyeon' && gahyeonOverride) return gahyeonOverride
  const normalized = activity.trim().toLowerCase().replaceAll('-', '_')
  const mediaPath = profile.activityMediaPaths?.[normalized] ?? profile.mediaPath
  return new URL(mediaPath, baseUrl).href
}
