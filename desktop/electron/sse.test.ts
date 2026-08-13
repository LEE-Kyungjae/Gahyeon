import { describe, expect, it } from 'vitest'
import {
  admitDurableEventId,
  DESKTOP_PRESENTATION_EVENT_TYPES,
  isDesktopPresentationEvent,
  parseEventBlock,
  parseEventStream,
} from './sse.js'
import { gahyeonPresentationEventTypes } from '../src/gahyeon-api.js'

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

  it('preserves CRLF when each carriage return is split from its line feed', async () => {
    const encoder = new TextEncoder()
    const chunks = [
      'id: 19\r', '\nevent: conversation.completed\r',
      '\ndata: {"content":"안녕"}\r', '\n\r', '\n',
    ]
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
        controller.close()
      },
    })

    const events = []
    for await (const event of parseEventStream(stream)) events.push(event)
    expect(events).toEqual([{
      id: '19',
      event: 'conversation.completed',
      data: { content: '안녕' },
    }])
  })

  it('rejects duplicate, reordered and malformed durable event ids before IPC', () => {
    expect(admitDurableEventId(7, '8')).toEqual({ accepted: true, cursor: 8 })
    expect(admitDurableEventId(8, '8')).toEqual({ accepted: false, cursor: 8 })
    expect(admitDurableEventId(8, '6')).toEqual({ accepted: false, cursor: 8 })
    expect(admitDurableEventId(8, 'NaN')).toEqual({ accepted: false, cursor: 8 })
    expect(admitDurableEventId(8)).toEqual({ accepted: true, cursor: 8 })
  })

  it('fails one connection instead of retaining an unbounded incomplete event', async () => {
    const encoder = new TextEncoder()
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(`data: ${'x'.repeat(65_536)}`))
        controller.close()
      },
    })

    const consume = async () => {
      for await (const ignored of parseEventStream(stream)) void ignored
    }
    await expect(consume()).rejects.toThrow('bounded parser capacity')
  })

  it('allows a large network chunk containing only bounded events', async () => {
    const encoder = new TextEncoder()
    const block = 'event: conversation.delta\ndata: {"delta":"ok"}\n\n'
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(block.repeat(2_000)))
        controller.close()
      },
    })

    let count = 0
    for await (const ignored of parseEventStream(stream)) {
      void ignored
      count++
    }
    expect(count).toBe(2_000)
  })

  it('keeps Electron IPC event admission aligned with the browser bridge', () => {
    expect(DESKTOP_PRESENTATION_EVENT_TYPES).toEqual(gahyeonPresentationEventTypes)
    expect(isDesktopPresentationEvent('conversation.completed')).toBe(true)
    expect(isDesktopPresentationEvent('future.untrusted.event')).toBe(false)
  })

  it('fails the connection on malformed UTF-8 instead of forwarding replacement text', async () => {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new Uint8Array([0x64, 0x61, 0x74, 0x61, 0x3a, 0x20, 0xc3, 0x28]))
        controller.close()
      },
    })
    const consume = async () => {
      for await (const ignored of parseEventStream(stream)) void ignored
    }
    await expect(consume()).rejects.toThrow()
  })
})
