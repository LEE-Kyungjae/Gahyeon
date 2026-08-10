import type { GahyeonDesktopEvent } from '../gahyeon-api'

export interface Vector3State {
  x: number
  y: number
  z: number
}

export interface StageState {
  room: string
  position: Vector3State
  activity: string
  expression: string
  expressionIntensity: number
  speaking: boolean
}

export const initialStageState: StageState = {
  room: 'bedroom',
  position: { x: 0, y: 0, z: 0 },
  activity: 'idle',
  expression: 'neutral',
  expressionIntensity: 0,
  speaking: false,
}

export function reduceStageEvent(state: StageState, event: GahyeonDesktopEvent): StageState {
  const payload = eventPayload(event.data)
  switch (event.event) {
    case 'avatar.expression':
      return {
        ...state,
        expression: text(payload.expression, state.expression),
        expressionIntensity: clamp(number(payload.intensity, state.expressionIntensity), 0, 1),
      }
    case 'avatar.speech.started':
      return { ...state, speaking: true }
    case 'avatar.speech.stopped':
      return { ...state, speaking: false }
    case 'character.moved':
      return {
        ...state,
        room: text(payload.room, state.room),
        position: vector(payload.position, state.position),
      }
    case 'behavior.activity.changed':
      return { ...state, activity: text(payload.activity, state.activity) }
    case 'world.state.restored':
      return {
        ...state,
        room: text(payload.room, state.room),
        position: vector(payload.position, state.position),
        activity: text(payload.activity, state.activity),
        expression: text(payload.expression, state.expression),
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

function number(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function clamp(value: number, minimum: number, maximum: number) {
  return Math.min(maximum, Math.max(minimum, value))
}
