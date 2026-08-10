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

export interface GahyeonDesktopEvent {
  event: string
  id?: string
  data: unknown
}

export interface GahyeonDesktopBridge {
  sendMessage(request: MessageRequest): Promise<MessageResponse>
  getWorldState(worldId: string): Promise<unknown>
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
    if (!response.ok) throw new Error(`Gahyeon Core 응답 오류 (${response.status})`)
    return response.json() as Promise<MessageResponse>
  },
  async getWorldState(worldId) {
    const response = await fetch(`/api/gahyeon/desktop/worlds/${encodeURIComponent(worldId)}`)
    if (!response.ok) throw new Error(`World State 응답 오류 (${response.status})`)
    return response.json()
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
      data: { message: 'Gahyeon Core event stream에 연결할 수 없습니다.' },
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
