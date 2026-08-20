import { describe, expect, it } from 'vitest'
import {
  characterActivityMediaUrl,
  characterMediaUrl,
  defaultDesktopCharacterId,
  desktopCharacter,
  desktopCharacterManifest,
  parseDesktopCharacterManifest,
  primaryDesktopCharacterId,
  restoreDesktopCharacter,
} from './character-catalog'

describe('desktop character catalog', () => {
  it('declares Gahyeon as the primary and default character', () => {
    expect(primaryDesktopCharacterId).toBe('gahyeon')
    expect(defaultDesktopCharacterId).toBe('gahyeon')
    expect(desktopCharacterManifest.characters[0].id).toBe('gahyeon')
  })

  it('restores a supported character and rejects stale values', () => {
    expect(restoreDesktopCharacter({ getItem: () => 'diana' })).toBe('diana')
    expect(restoreDesktopCharacter({ getItem: () => 'unknown' })).toBe('gahyeon')
    expect(restoreDesktopCharacter({ getItem: () => null })).toBe('gahyeon')
  })

  it('resolves a distinct bundled preview for each character', () => {
    const base = 'http://localhost:5173/index.html'
    expect(characterMediaUrl(desktopCharacter('gahyeon'), base)).toContain('gahyeon-')
    expect(characterMediaUrl(desktopCharacter('stella-lily'), base)).toContain('stella-lily-')
    expect(characterMediaUrl(desktopCharacter('ururu'), base)).toContain('ururu-')
    expect(characterMediaUrl(desktopCharacter('diana'), base)).toContain('diana-')
  })

  it('keeps the new Unreal living-character candidates explicitly unapproved', () => {
    expect(desktopCharacter('stella-lily').productionReady).toBe(false)
    expect(desktopCharacter('ururu').productionReady).toBe(false)
  })

  it('selects retained action media and falls back to the living idle', () => {
    const base = 'http://localhost:5173/index.html'
    expect(characterActivityMediaUrl(desktopCharacter('stella-lily'), base, 'walk'))
      .toContain('/actions/stella-lily/walk-v644.webm')
    expect(characterActivityMediaUrl(desktopCharacter('ururu'), base, 'conversation'))
      .toContain('/actions/ururu/narration-v644.webm')
    expect(characterActivityMediaUrl(desktopCharacter('ururu'), base, 'unknown'))
      .toContain('ururu-living-idle-v640.webm')
  })

  it('applies the legacy Gahyeon override only to Gahyeon', () => {
    const override = 'https://assets.example/gahyeon.webm'
    expect(characterMediaUrl(desktopCharacter('gahyeon'), 'http://localhost/', override)).toBe(override)
    expect(characterMediaUrl(desktopCharacter('diana'), 'http://localhost/', override)).not.toBe(override)
  })

  it('keeps Diana personality, memory, voice, and expression bindings separate', () => {
    const gahyeon = desktopCharacter('gahyeon')
    const diana = desktopCharacter('diana')
    expect(diana.personaPrompt).not.toBe(gahyeon.personaPrompt)
    expect(diana.memoryNamespace).not.toBe(gahyeon.memoryNamespace)
    expect(diana.voiceProfile).not.toBe(gahyeon.voiceProfile)
    expect(diana.expressionProfile).not.toBe(gahyeon.expressionProfile)
  })

  it('accepts an additional personality without changing TypeScript code', () => {
    const extra = {
      ...desktopCharacterManifest,
      characters: [...desktopCharacterManifest.characters, {
        id: 'new-persona', displayName: '새 인격', personaPrompt: 'prompts/characters/new-persona.txt',
        memoryNamespace: 'character:new-persona', voiceProfile: 'new-persona.assistant',
        expressionProfile: 'new-persona.metahuman', autonomousEnabled: false,
        mediaPath: './poc/new-persona.webm',
        unrealAssetPath: '/Game/Characters/NewPersona', productionReady: false,
      }],
    }
    expect(parseDesktopCharacterManifest(extra).characters.at(-1)?.id).toBe('new-persona')
  })

  it('fails closed for duplicate, unsafe, or unbound character definitions', () => {
    expect(() => parseDesktopCharacterManifest({
      ...desktopCharacterManifest,
      characters: [...desktopCharacterManifest.characters, desktopCharacterManifest.characters[0]],
    })).toThrow(/unique/)
    expect(() => parseDesktopCharacterManifest({
      ...desktopCharacterManifest,
      characters: [{ ...desktopCharacterManifest.characters[0], id: '../escape' }],
      primaryCharacterId: '../escape', defaultCharacterId: '../escape',
    })).toThrow(/invalid/)
    expect(() => parseDesktopCharacterManifest({
      ...desktopCharacterManifest, primaryCharacterId: 'missing',
    })).toThrow(/primary character/)
  })
})
