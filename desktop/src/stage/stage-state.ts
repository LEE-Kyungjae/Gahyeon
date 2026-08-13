import type { GahyeonDesktopEvent } from '../gahyeon-api'

export interface Vector3State {
  x: number
  y: number
  z: number
}

/** Renderer-local view of a Core-owned action that has not committed yet. */
export interface PendingWorldAction {
  actionId: string
  expectedRevision: number
  room: string
  position: Vector3State
  activity: string
  interactionTarget?: string
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
  pendingWorldAction?: PendingWorldAction
  deferredWorldAction?: PendingWorldAction
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
        ...worldActionRevision(state, revision),
        revision,
        expression: text(payload.expression, state.expression),
        expressionIntensity: clamp(number(payload.intensity, state.expressionIntensity), 0, 1),
      }
    case 'avatar.speech.started':
      return { ...state, revision, activity: 'conversation', speaking: true }
    case 'avatar.speech.level':
      return {
        ...state,
        revision,
        speechAmplitude: clamp(number(payload.level, state.speechAmplitude), 0, 1),
      }
    case 'avatar.speech.stopped':
      return { ...state, revision, activity: state.speaking ? 'idle' : state.activity,
        speaking: false, speechAmplitude: 0 }
    case 'character.moved':
      return {
        ...state,
        ...worldActionRevision(state, revision),
        revision,
        room: text(payload.room, text(payload.currentRoom, state.room)),
        position: vector(payload.position, state.position),
      }
    case 'behavior.activity.changed':
      return {
        ...state,
        ...worldActionRevision(state, revision),
        revision,
        activity: text(payload.activity, state.activity),
      }
    case 'world.state.changed':
    case 'world.state.restored': {
      const emotion = object(payload.emotion)
      return {
        ...state,
        ...worldActionRevision(state, revision),
        revision,
        room: text(payload.room, text(payload.currentRoom, state.room)),
        position: vector(payload.position, state.position),
        activity: lowerText(payload.activity, state.activity),
        expression: text(
          payload.expression,
          text(emotion?.name, text(payload.emotion, state.expression)),
        ),
        expressionIntensity: clamp(
          number(
            payload.intensity,
            number(emotion?.intensity, number(payload.emotionIntensity, state.expressionIntensity)),
          ),
          0,
          1,
        ),
      }
    }
    case 'world.transition.target': {
      const action = pendingWorldAction(payload)
      // The target is meaningful only for the exact authoritative state from
      // which Core planned it. A gap is recovered by the next snapshot/event.
      if (!action || action.expectedRevision < state.revision) return state
      return action.expectedRevision === state.revision
        ? { ...state, pendingWorldAction: action, deferredWorldAction: undefined }
        : { ...state, deferredWorldAction: action }
    }
    case 'character.action.result': {
      const actionId = text(payload.actionId, '')
      if (actionId !== state.pendingWorldAction?.actionId
          && actionId !== state.deferredWorldAction?.actionId) return state
      return {
        ...state,
        pendingWorldAction: actionId === state.pendingWorldAction?.actionId
          ? undefined : state.pendingWorldAction,
        deferredWorldAction: actionId === state.deferredWorldAction?.actionId
          ? undefined : state.deferredWorldAction,
      }
    }
    case 'conversation.started':
      return { ...state, activity: 'attention' }
    case 'perception.voice.started':
      return { ...state, activity: 'listening' }
    case 'perception.voice.ended':
      return { ...state, activity: 'thinking' }
    case 'perception.voice.cancelled':
      return { ...state, activity: 'idle' }
    case 'conversation.completed':
      return { ...state, activity: state.speaking ? 'conversation' : 'idle' }
    case 'conversation.failed':
    case 'conversation.cancelled':
      return { ...state, activity: 'idle', speaking: false, speechAmplitude: 0 }
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
    || type === 'world.state.changed'
    || type === 'world.state.restored'
    || type === 'world.transition.target'
    || type === 'character.action.result'
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

function object(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined
}

function pendingWorldAction(payload: Record<string, unknown>): PendingWorldAction | undefined {
  const actionId = text(payload.actionId, '')
  const room = text(payload.room, '')
  const activity = lowerText(payload.activity, '')
  const expectedRevision = number(payload.expectedRevision, -1)
  const rawPosition = object(payload.position)
  if (!actionId || !room || !activity || !Number.isSafeInteger(expectedRevision)
      || expectedRevision < 0 || !rawPosition) return undefined
  const position = vector(rawPosition, { x: Number.NaN, y: Number.NaN, z: Number.NaN })
  if (!Number.isFinite(position.x) || !Number.isFinite(position.y)
      || !Number.isFinite(position.z)) return undefined
  const interactionTarget = text(payload.interactionTarget, '') || undefined
  return { actionId, expectedRevision, room, position, activity, interactionTarget }
}

function worldActionRevision(state: StageState, revision: number) {
  let pendingWorldAction = state.pendingWorldAction
  let deferredWorldAction = state.deferredWorldAction
  if (pendingWorldAction && revision > pendingWorldAction.expectedRevision) {
    pendingWorldAction = undefined
  }
  if (deferredWorldAction) {
    if (revision === deferredWorldAction.expectedRevision) {
      pendingWorldAction = deferredWorldAction
      deferredWorldAction = undefined
    }
    else if (revision > deferredWorldAction.expectedRevision) {
      deferredWorldAction = undefined
    }
  }
  return { pendingWorldAction, deferredWorldAction }
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
