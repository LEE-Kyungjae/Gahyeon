import { describe, expect, it } from 'vitest'
import { readBoundedArrayBuffer as readElectronResponse } from './bounded-response.js'
import { readBoundedArrayBuffer as readBrowserResponse } from '../src/audio/bounded-response.js'

describe.each([
  ['Electron', readElectronResponse],
  ['Browser', readBrowserResponse],
])('%s bounded audio response', (_runtime, readResponse) => {
  const tooLarge = () => new Error('too large')

  it('rejects a declared oversized response before reading the body', async () => {
    const response = new Response(new Uint8Array([1]), {
      headers: { 'content-length': '17' },
    })
    await expect(readResponse(response, 16, tooLarge)).rejects.toThrow('too large')
    expect(response.bodyUsed).toBe(false)
  })

  it('cancels a chunked response as soon as its measured size crosses the limit', async () => {
    let cancelled = false
    const response = new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new Uint8Array(10))
        controller.enqueue(new Uint8Array(7))
      },
      cancel() { cancelled = true },
    }))
    await expect(readResponse(response, 16, tooLarge)).rejects.toThrow('too large')
    expect(cancelled).toBe(true)
  })

  it('joins a bounded chunked response without changing its bytes', async () => {
    const response = new Response(new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new Uint8Array([1, 2]))
        controller.enqueue(new Uint8Array([3, 4]))
        controller.close()
      },
    }))
    const result = await readResponse(response, 4, tooLarge)
    expect([...new Uint8Array(result)]).toEqual([1, 2, 3, 4])
  })
})
