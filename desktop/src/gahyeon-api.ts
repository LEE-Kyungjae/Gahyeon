import { GahyeonClientError } from './client-error'
import { readBoundedArrayBuffer } from './audio/bounded-response'

export interface MessageRequest {
  sessionId: string
  requestId: string
  installationId: string
  displayName: string
  message: string
}

export interface MessageResponse {
  runId: string
  content: string
}

export interface IdentityLinkResponse { linked: boolean; credentialExpiresAt?: string | null }

export interface SpeechStatus {
  transcriptionReady: boolean
  synthesisReady: boolean
}

export interface SpeechSegment {
  index: number
  text: string
}

export interface AudioPayload {
  data: ArrayBuffer
  mediaType: string
}

export interface WorldActionCompletionRequest {
  installationId: string
  actionId: string
  expectedRevision: number
  finalPosition: { x: number, y: number, z: number }
}

export interface WorldActionCompletionResponse {
  result: 'COMMITTED' | 'DUPLICATE' | 'STALE' | 'CONFLICT' | 'INVALID' | 'RECORDED_FAILURE'
}

export interface GahyeonDesktopEvent {
  event: string
  id?: string
  data: unknown
}

export const gahyeonPresentationEventTypes = [
  'stream.connected',
  'conversation.started',
  'conversation.delta',
  'conversation.completed',
  'conversation.failed',
  'conversation.cancelled',
  'avatar.expression',
  'avatar.speech.started',
  'avatar.speech.level',
  'avatar.speech.stopped',
  'character.moved',
  'behavior.activity.changed',
  'world.state.changed',
  'world.state.restored',
  'world.transition.target',
  'character.action.result',
] as const

export interface GahyeonDesktopBridge {
  sendMessage(request: MessageRequest): Promise<MessageResponse>
  linkDesktop(request: { code: string, installationId: string, displayName: string }): Promise<IdentityLinkResponse>
  getIdentityLinkStatus(installationId: string): Promise<IdentityLinkResponse>
  unlinkCurrentDesktop(installationId: string): Promise<void>
  cancelConversation(sessionId: string, installationId: string): Promise<void>
  cancelSpeechRequests(): void
  getWorldState(worldId: string): Promise<unknown>
  completeWorldAction(
    worldId: string,
    request: WorldActionCompletionRequest,
  ): Promise<WorldActionCompletionResponse>
  getSpeechStatus(): Promise<SpeechStatus>
  transcribeWav(audio: ArrayBuffer): Promise<string>
  prepareSpeech(text: string): Promise<SpeechSegment[]>
  synthesizeSpeech(segment: SpeechSegment): Promise<AudioPayload>
  subscribeEvents(
    request: { sessionId: string, installationId: string, afterSequence: number },
    listener: (event: GahyeonDesktopEvent) => void,
  ): () => void
}

export function getGahyeonBridge(): GahyeonDesktopBridge {
  return window.gahyeon ?? browserBridge
}

const CONVERSATION_TIMEOUT_MILLIS = 10_000
const METADATA_TIMEOUT_MILLIS = 5_000
const MAXIMUM_SPEECH_AUDIO_BYTES = 16 * 1024 * 1024
let browserConversationController = new AbortController()

export const browserBridge: GahyeonDesktopBridge = {
  async sendMessage(request) {
    const response = await conversationFetch(
      `/api/gahyeon/desktop/conversations/${encodeURIComponent(request.sessionId)}/messages`,
      {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(request),
      },
      browserConversationController.signal,
    )
    if (!response.ok) throw new GahyeonClientError('conversation', String(response.status))
    return response.json() as Promise<MessageResponse>
  },
  async linkDesktop(request) {
    void request
    throw new GahyeonClientError('identityLink', 'nativeDesktopRequired')
  },
  async getIdentityLinkStatus(installationId) {
    void installationId
    return { linked: false }
  },
  async unlinkCurrentDesktop() {
    throw new GahyeonClientError('identityLink', 'nativeDesktopRequired')
  },
  async cancelConversation(sessionId, installationId) {
    browserConversationController.abort()
    browserConversationController = new AbortController()
    const response = await speechFetch(
      `/api/gahyeon/desktop/conversations/${encodeURIComponent(sessionId)}/active?installationId=${encodeURIComponent(installationId)}`,
      { method: 'DELETE' },
      5_000,
      'conversationCancel',
      AbortSignal.timeout(5_000),
    )
    if (!response.ok) throw new GahyeonClientError('conversationCancel', String(response.status))
  },
  cancelSpeechRequests() {
    browserSpeechController.abort()
    browserSpeechController = new AbortController()
  },
  async getWorldState(worldId) {
    const response = await speechFetch(
      `/api/gahyeon/desktop/worlds/${encodeURIComponent(worldId)}`,
      {}, METADATA_TIMEOUT_MILLIS, 'world', AbortSignal.timeout(METADATA_TIMEOUT_MILLIS),
    )
    if (!response.ok) throw new GahyeonClientError('world', String(response.status))
    return response.json()
  },
  async completeWorldAction(worldId, request) {
    const response = await speechFetch(
      `/api/gahyeon/desktop/worlds/${encodeURIComponent(worldId)}/actions/${encodeURIComponent(request.actionId)}/complete`,
      {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          installationId: request.installationId,
          expectedRevision: request.expectedRevision,
          x: request.finalPosition.x,
          y: request.finalPosition.y,
          z: request.finalPosition.z,
        }),
      },
      METADATA_TIMEOUT_MILLIS,
      'worldActionCompletion',
      AbortSignal.timeout(METADATA_TIMEOUT_MILLIS),
    )
    if (!response.ok) {
      throw new GahyeonClientError('worldActionCompletion', String(response.status))
    }
    return response.json() as Promise<WorldActionCompletionResponse>
  },
  async getSpeechStatus() {
    const response = await speechFetch(
      '/api/gahyeon/desktop/speech/status',
      {}, METADATA_TIMEOUT_MILLIS, 'speechStatus', AbortSignal.timeout(METADATA_TIMEOUT_MILLIS),
    )
    if (!response.ok) throw new GahyeonClientError('speechStatus', String(response.status))
    return response.json() as Promise<SpeechStatus>
  },
  async transcribeWav(audio) {
    const response = await speechFetch('/api/gahyeon/desktop/speech/transcriptions', {
      method: 'POST',
      headers: { 'content-type': 'audio/wav' },
      body: audio,
    }, 10_000, 'transcription', browserSpeechController.signal)
    if (!response.ok) throw new GahyeonClientError('transcription', String(response.status))
    const body = await response.json() as { transcript: string }
    return body.transcript
  },
  async prepareSpeech(text) {
    const response = await speechFetch('/api/gahyeon/desktop/speech/segments', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ text }),
    }, METADATA_TIMEOUT_MILLIS, 'speechSegments', browserSpeechController.signal)
    if (!response.ok) throw new GahyeonClientError('speechSegments', String(response.status))
    return response.json() as Promise<SpeechSegment[]>
  },
  async synthesizeSpeech(segment) {
    const response = await speechFetch('/api/gahyeon/desktop/speech/synthesis', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ...segment, voiceProfile: 'gahyeon.assistant' }),
    }, 25_000, 'synthesis', browserSpeechController.signal)
    if (!response.ok) throw new GahyeonClientError('synthesis', String(response.status))
    return {
      data: await readBoundedArrayBuffer(
        response,
        MAXIMUM_SPEECH_AUDIO_BYTES,
        () => new GahyeonClientError('synthesis', 'responseTooLarge'),
      ),
      mediaType: response.headers.get('content-type') ?? 'audio/wav',
    }
  },
  subscribeEvents(request, listener) {
    const query = new URLSearchParams({
      sessionId: request.sessionId,
      installationId: request.installationId,
      afterSequence: String(request.afterSequence),
    })
    const source = new EventSource(`/api/gahyeon/desktop/events?${query}`)
    for (const eventType of gahyeonPresentationEventTypes) {
      source.addEventListener(eventType, (event) => {
        const message = event as MessageEvent<string>
        listener({
          event: eventType,
          id: message.lastEventId || undefined,
          data: parseData(message.data),
        })
      })
    }
    source.onerror = () => listener({
      event: 'stream.error',
      data: { code: 'eventStream' },
    })
    return () => source.close()
  },
}

let browserSpeechController = new AbortController()

async function conversationFetch(
  input: RequestInfo | URL,
  init: RequestInit,
  cancellation: AbortSignal,
) {
  try {
    return await fetch(input, {
      ...init,
      signal: AbortSignal.any([
        cancellation,
        AbortSignal.timeout(CONVERSATION_TIMEOUT_MILLIS),
      ]),
    })
  }
  catch (error) {
    if (error instanceof DOMException && error.name === 'TimeoutError') {
      throw new GahyeonClientError('conversation', 'timeout')
    }
    throw error
  }
}

async function speechFetch(
  input: RequestInfo | URL,
  init: RequestInit,
  timeoutMs: number,
  code: string,
  cancellation: AbortSignal,
) {
  try {
    return await fetch(input, {
      ...init,
      signal: AbortSignal.any([cancellation, AbortSignal.timeout(timeoutMs)]),
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'TimeoutError') {
      throw new GahyeonClientError(code, 'timeout')
    }
    throw error
  }
}

function parseData(value: string): unknown {
  try {
    return JSON.parse(value)
  }
  catch {
    return value
  }
}
