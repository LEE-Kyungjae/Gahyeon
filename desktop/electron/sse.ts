export interface ParsedServerEvent {
  event: string
  id?: string
  data: unknown
}

export async function* parseEventStream(
  stream: ReadableStream<Uint8Array>,
): AsyncGenerator<ParsedServerEvent> {
  const decoder = new TextDecoder()
  let buffer = ''
  for await (const chunk of stream) {
    buffer += decoder.decode(chunk, { stream: true }).replaceAll('\r\n', '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const parsed = parseEventBlock(block)
      if (parsed) yield parsed
      boundary = buffer.indexOf('\n\n')
    }
  }
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
