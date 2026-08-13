export type ConversationTerminalType =
  | 'conversation.completed'
  | 'conversation.failed'
  | 'conversation.cancelled'

export interface ConversationTerminal {
  type: ConversationTerminalType
  requestId: string
  runId?: string
  content?: string
}

export function isUnsuccessfulConversationTerminal(type: string) {
  return type === 'conversation.failed' || type === 'conversation.cancelled'
}

export interface TerminalMessage {
  id: string
  text: string
  requestId?: string
}

export interface OrphanCompletion {
  id?: string
  text: string
}

/** Parses the durable Core event envelope without trusting arbitrary payload shapes. */
export function parseConversationTerminal(
  type: string,
  data: unknown,
): ConversationTerminal | undefined {
  if (!isTerminalType(type) || !data || typeof data !== 'object') return undefined
  const envelope = data as Record<string, unknown>
  if (typeof envelope.correlationId !== 'string' || !envelope.correlationId.trim()) return undefined
  const payload = envelope.payload && typeof envelope.payload === 'object'
    ? envelope.payload as Record<string, unknown>
    : {}
  return {
    type,
    requestId: envelope.correlationId,
    runId: optionalText(payload.runId),
    content: optionalText(payload.content),
  }
}

/** Reconciles an SSE terminal without competing with a still-active local POST owner. */
export function reconcileConversationTerminal(
  messages: TerminalMessage[],
  locallyOwned: boolean,
  terminal: ConversationTerminal,
): OrphanCompletion | undefined {
  const entry = messages.find(message => message.requestId === terminal.requestId)
  if (terminal.type === 'conversation.completed') {
    // A long SSE outage can outlive the short request-ID TTL while the HTTP
    // response is already rendered. Core run identity remains authoritative.
    if (!entry && terminal.runId
      && messages.some(message => message.id === terminal.runId)) return undefined
    if (entry) {
      if (terminal.runId) entry.id = terminal.runId
      if (terminal.content !== undefined) entry.text = terminal.content
    }
    else if (!locallyOwned && terminal.content !== undefined) {
      return { id: terminal.runId, text: terminal.content }
    }
  }
  if (!locallyOwned && entry) delete entry.requestId
  return undefined
}

function isTerminalType(value: string): value is ConversationTerminalType {
  return value === 'conversation.completed'
    || value === 'conversation.failed'
    || value === 'conversation.cancelled'
}

function optionalText(value: unknown) {
  return typeof value === 'string' && value.trim() ? value : undefined
}
