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

export interface GahyeonDesktopEvent {
  event: string
  id?: string
  data: unknown
}

export interface GahyeonDesktopBridge {
  sendMessage(request: MessageRequest): Promise<MessageResponse>
  getWorldState(worldId: string): Promise<unknown>
  getSpeechStatus(): Promise<SpeechStatus>
  transcribeWav(audio: ArrayBuffer): Promise<string>
  prepareSpeech(text: string): Promise<SpeechSegment[]>
  synthesizeSpeech(segment: SpeechSegment): Promise<AudioPayload>
  subscribeEvents(
    request: { sessionId: string, afterSequence: number },
    listener: (event: GahyeonDesktopEvent) => void,
  ): () => void
}

export function getGahyeonBridge(): GahyeonDesktopBridge {
  return window.gahyeon ?? browserBridge
}

const browserBridge: GahyeonDesktopBridge = {
  async sendMessage(request) {
    const response = await fetch(
      `/api/gahyeon/desktop/conversations/${encodeURIComponent(request.sessionId)}/messages`,
      {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(request),
      },
    )
    if (!response.ok) throw new GahyeonClientError('conversation', String(response.status))
    return response.json() as Promise<MessageResponse>
  },
  async getWorldState(worldId) {
    const response = await fetch(`/api/gahyeon/desktop/worlds/${encodeURIComponent(worldId)}`)
    if (!response.ok) throw new GahyeonClientError('world', String(response.status))
    return response.json()
  },
  async getSpeechStatus() {
    const response = await fetch('/api/gahyeon/desktop/speech/status')
    if (!response.ok) throw new GahyeonClientError('speechStatus', String(response.status))
    return response.json() as Promise<SpeechStatus>
  },
  async transcribeWav(audio) {
    const response = await fetch('/api/gahyeon/desktop/speech/transcriptions', {
      method: 'POST',
      headers: { 'content-type': 'audio/wav' },
      body: audio,
    })
    if (!response.ok) throw new GahyeonClientError('transcription', String(response.status))
    const body = await response.json() as { transcript: string }
    return body.transcript
  },
  async prepareSpeech(text) {
    const response = await fetch('/api/gahyeon/desktop/speech/segments', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ text }),
    })
    if (!response.ok) throw new GahyeonClientError('speechSegments', String(response.status))
    return response.json() as Promise<SpeechSegment[]>
  },
  async synthesizeSpeech(segment) {
    const response = await fetch('/api/gahyeon/desktop/speech/synthesis', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ...segment, voiceProfile: 'gahyeon.assistant' }),
    })
    if (!response.ok) throw new GahyeonClientError('synthesis', String(response.status))
    return { data: await response.arrayBuffer(), mediaType: response.headers.get('content-type') ?? 'audio/wav' }
  },
  subscribeEvents(request, listener) {
    const query = new URLSearchParams({
      sessionId: request.sessionId,
      afterSequence: String(request.afterSequence),
    })
    const source = new EventSource(`/api/gahyeon/desktop/events?${query}`)
    const eventTypes = [
      'stream.connected',
      'conversation.started',
      'conversation.completed',
      'conversation.failed',
    ]
    for (const eventType of eventTypes) {
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

function parseData(value: string): unknown {
  try {
    return JSON.parse(value)
  }
  catch {
    return value
  }
}
import { GahyeonClientError } from './client-error'
