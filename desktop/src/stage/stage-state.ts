import type { GahyeonDesktopEvent } from '../gahyeon-api'

export interface Vector3State {
  x: number
  y: number
  z: number
}

export interface StageState {
  revision: number
  room: string
  position: Vector3State
  activity: string
  expression: string
  expressionIntensity: number
  speaking: boolean
  speechAmplitude: number
}

export const initialStageState: StageState = {
  revision: 0,
  room: 'bedroom',
  position: { x: 0, y: 0, z: 0 },
  activity: 'idle',
  expression: 'neutral',
  expressionIntensity: 0,
  speaking: false,
  speechAmplitude: 0,
}

export function reduceStageEvent(state: StageState, event: GahyeonDesktopEvent): StageState {
  const payload = eventPayload(event.data)
  const revision = number(payload.revision, state.revision)
  if (isWorldEvent(event.event) && revision < state.revision) return state
  switch (event.event) {
    case 'avatar.expression':
      return {
        ...state,
        revision,
        expression: text(payload.expression, state.expression),
        expressionIntensity: clamp(number(payload.intensity, state.expressionIntensity), 0, 1),
      }
    case 'avatar.speech.started':
      return { ...state, revision, speaking: true }
    case 'avatar.speech.level':
      return {
        ...state,
        revision,
        speechAmplitude: clamp(number(payload.level, state.speechAmplitude), 0, 1),
      }
    case 'avatar.speech.stopped':
      return { ...state, revision, speaking: false, speechAmplitude: 0 }
    case 'character.moved':
      return {
        ...state,
        revision,
        room: text(payload.room, text(payload.currentRoom, state.room)),
        position: vector(payload.position, state.position),
      }
    case 'behavior.activity.changed':
      return { ...state, revision, activity: text(payload.activity, state.activity) }
    case 'world.state.restored':
      return {
        ...state,
        revision,
        room: text(payload.room, text(payload.currentRoom, state.room)),
        position: vector(payload.position, state.position),
        activity: lowerText(payload.activity, state.activity),
        expression: text(payload.expression, text(payload.emotion, state.expression)),
      }
    case 'conversation.started':
      return { ...state, activity: 'attention' }
    case 'conversation.completed':
    case 'conversation.failed':
      return { ...state, activity: 'idle' }
    default:
      return state
  }
}

function isWorldEvent(type: string) {
  return type === 'avatar.expression'
    || type === 'avatar.speech.started'
    || type === 'avatar.speech.level'
    || type === 'avatar.speech.stopped'
    || type === 'character.moved'
    || type === 'behavior.activity.changed'
    || type === 'world.state.restored'
}

function eventPayload(data: unknown): Record<string, unknown> {
  if (!data || typeof data !== 'object') return {}
  const envelope = data as Record<string, unknown>
  const payload = envelope.payload
  return payload && typeof payload === 'object' ? payload as Record<string, unknown> : envelope
}

function vector(value: unknown, fallback: Vector3State): Vector3State {
  if (!value || typeof value !== 'object') return fallback
  const candidate = value as Record<string, unknown>
  return {
    x: number(candidate.x, fallback.x),
    y: number(candidate.y, fallback.y),
    z: number(candidate.z, fallback.z),
  }
}

function text(value: unknown, fallback: string) {
  return typeof value === 'string' && value.trim() ? value : fallback
}

function lowerText(value: unknown, fallback: string) {
  return text(value, fallback).toLowerCase()
}

function number(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function clamp(value: number, minimum: number, maximum: number) {
  return Math.min(maximum, Math.max(minimum, value))
}
