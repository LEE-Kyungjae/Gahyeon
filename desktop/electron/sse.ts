export interface ParsedServerEvent {
  event: string
  id?: string
  data: unknown
}

const MAXIMUM_EVENT_BLOCK_CHARACTERS = 65_536
export const DESKTOP_PRESENTATION_EVENT_TYPES = [
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
const PRESENTATION_EVENT_TYPE_SET = new Set<string>(DESKTOP_PRESENTATION_EVENT_TYPES)

export function isDesktopPresentationEvent(type: string) {
  return PRESENTATION_EVENT_TYPE_SET.has(type)
}

export async function* parseEventStream(
  stream: ReadableStream<Uint8Array>,
): AsyncGenerator<ParsedServerEvent> {
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let buffer = ''
  for await (const chunk of stream) {
    buffer += decoder.decode(chunk, { stream: true })
    // Preserve one trailing CR until the next chunk reveals whether it is the
    // first half of CRLF. Replacing it eagerly would turn a split CRLF into
    // two newlines and could terminate an event between its fields.
    buffer = normalizeCompleteNewlines(buffer)
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      if (boundary > MAXIMUM_EVENT_BLOCK_CHARACTERS) {
        throw new Error('SSE event block exceeds the bounded parser capacity')
      }
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const parsed = parseEventBlock(block)
      if (parsed) yield parsed
      boundary = buffer.indexOf('\n\n')
    }
    if (buffer.length > MAXIMUM_EVENT_BLOCK_CHARACTERS) {
      throw new Error('SSE event block exceeds the bounded parser capacity')
    }
  }
}

function normalizeCompleteNewlines(value: string) {
  return value.replaceAll('\r\n', '\n').replace(/\r(?!$)/g, '\n')
}

export function parseEventBlock(block: string): ParsedServerEvent | undefined {
  let event = 'message'
  let id: string | undefined
  const data: string[] = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trimStart()
    else if (line.startsWith('id:')) id = line.slice(3).trimStart()
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (data.length === 0) return undefined
  const raw = data.join('\n')
  try {
    return { event, id, data: JSON.parse(raw) }
  }
  catch {
    return { event, id, data: raw }
  }
}

/** IPC-side replay fence; renderer repeats the check before applying presentation side effects. */
export function admitDurableEventId(current: number, id?: string) {
  const safeCurrent = Number.isSafeInteger(current) && current >= 0 ? current : 0
  if (id === undefined) return { accepted: true, cursor: safeCurrent }
  if (!/^(0|[1-9]\d*)$/.test(id)) return { accepted: false, cursor: safeCurrent }
  const candidate = Number(id)
  if (!Number.isSafeInteger(candidate) || candidate <= safeCurrent) {
    return { accepted: false, cursor: safeCurrent }
  }
  return { accepted: true, cursor: candidate }
}
