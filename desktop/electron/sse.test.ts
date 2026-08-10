import { describe, expect, it } from 'vitest'
import { parseEventBlock, parseEventStream } from './sse.js'

describe('SSE parser', () => {
  it('parses named JSON events and cursors', () => {
    expect(parseEventBlock('id: 19\nevent: conversation.completed\ndata: {"content":"안녕"}'))
      .toEqual({
        id: '19',
        event: 'conversation.completed',
        data: { content: '안녕' },
      })
  })

  it('preserves events split across network chunks', async () => {
    const encoder = new TextEncoder()
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: conversation.'))
        controller.enqueue(encoder.encode('started\r\ndata: {"ok":true}\r\n\r\n'))
        controller.close()
      },
    })

    const events = []
    for await (const event of parseEventStream(stream)) events.push(event)
    expect(events).toEqual([{ event: 'conversation.started', data: { ok: true } }])
  })
})
