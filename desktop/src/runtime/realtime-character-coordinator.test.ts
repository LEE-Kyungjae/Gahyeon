import { describe, expect, it } from 'vitest'
import { RealtimeCharacterCoordinator } from './realtime-character-coordinator'

describe('RealtimeCharacterCoordinator', () => {
  it('reacts to VAD synchronously and remains alive during slow cognition', () => {
    const character = new RealtimeCharacterCoordinator(0)

    const generation = character.voiceStarted(10)
    expect(character.intents.resolve(10).channels.phase?.value).toBe('listening')
    character.voiceEnded(generation, 200)

    const waiting = character.intents.resolve(10_200)
    expect(waiting.channels.phase?.value).toBe('thinking')
    expect(waiting.channels.posture?.value).toBe('ambient_alive')
  })

  it('rejects late cognition after barge-in starts a new generation', () => {
    const character = new RealtimeCharacterCoordinator()
    const first = character.voiceStarted(0)
    character.voiceEnded(first, 100)
    expect(character.speechStarted(first, 1_000, 'old-utterance')).toBe(true)
    expect(character.intents.resolve(1_000).channels.phase?.value).toBe('speaking')

    const second = character.voiceStarted(1_100)
    expect(character.intents.resolve(1_100).channels.phase?.value).toBe('listening')
    expect(character.speechStarted(first, 1_200, 'late-old-utterance')).toBe(false)
    expect(character.intents.resolve(1_200).channels.speech).toBeUndefined()
    expect(character.intents.resolve(1_200).generation).toBe(second)
  })

  it('ignores stale voice-end and speech-end transitions', () => {
    const character = new RealtimeCharacterCoordinator()
    const first = character.voiceStarted(0)
    const second = character.voiceStarted(50)

    expect(character.voiceEnded(first, 60)).toBe(false)
    expect(character.speechEnded(first, 70)).toBe(false)
    expect(character.intents.resolve(70).channels.phase?.value).toBe('listening')
    expect(character.voiceEnded(second, 80)).toBe(true)
    expect(character.intents.resolve(80).channels.phase?.value).toBe('thinking')
  })

  it('clears the active utterance when playback actually ends', () => {
    const character = new RealtimeCharacterCoordinator()
    const generation = character.voiceStarted(0)
    character.voiceEnded(generation, 10)
    character.speechStarted(generation, 20, 'audio-1')

    expect(character.speechEnded(generation, 30)).toBe(true)
    const ended = character.intents.resolve(30)
    expect(ended.channels.phase?.value).toBe('idle')
    expect(ended.channels.speech?.value).toBe('')
  })

  it('returns to idle and invalidates late cognition after timeout', () => {
    const character = new RealtimeCharacterCoordinator(0, 500)
    const pending = character.voiceStarted(0)
    character.voiceEnded(pending, 100)

    expect(character.advance(599)).toBeUndefined()
    const current = character.advance(600)

    expect(current).toBe(pending + 1)
    expect(character.intents.resolve(600).channels.phase?.value).toBe('idle')
    expect(character.speechStarted(pending, 700, 'late')).toBe(false)
  })

  it('does not time out after actual playback starts', () => {
    const character = new RealtimeCharacterCoordinator(0, 500)
    const generation = character.voiceStarted(0)
    character.voiceEnded(generation, 100)
    character.speechStarted(generation, 300, 'audio')

    expect(character.advance(1_000)).toBeUndefined()
    expect(character.intents.resolve(1_000).channels.phase?.value).toBe('speaking')
  })
})
