import { afterEach, describe, expect, it, vi } from 'vitest'
import type { GahyeonDesktopBridge } from '../gahyeon-api'
import { SpeechPlayer } from './speech-player'

describe('SpeechPlayer', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('enters speaking only after the first audio source actually starts', async () => {
    const order: string[] = []
    class FakeSource {
      onended: null | (() => void) = null
      buffer?: AudioBuffer
      connect() {}
      disconnect() {}
      stop() {}
      start() {
        order.push('source.start')
        queueMicrotask(() => this.onended?.())
      }
    }
    class FakeAnalyser {
      fftSize = 256
      smoothingTimeConstant = 0
      connect() {}
      disconnect() {}
      getByteTimeDomainData(values: Uint8Array) { values.fill(128) }
    }
    class FakeAudioContext {
      destination = {}
      async resume() {}
      async close() {}
      async decodeAudioData() {
        order.push('decode')
        return {} as AudioBuffer
      }
      createBufferSource() { return new FakeSource() }
      createAnalyser() { return new FakeAnalyser() }
    }
    vi.stubGlobal('AudioContext', FakeAudioContext)
    vi.stubGlobal('requestAnimationFrame', () => 1)
    vi.stubGlobal('cancelAnimationFrame', () => {})
    const bridge = {
      async prepareSpeech() {
        order.push('prepare')
        return [{ index: 0, text: '첫 문장' }, { index: 1, text: '둘째 문장' }]
      },
      async synthesizeSpeech() {
        order.push('synthesize')
        return { data: new ArrayBuffer(8), mediaType: 'audio/wav' }
      },
    } as unknown as GahyeonDesktopBridge
    const player = new SpeechPlayer()

    await player.speak('두 문장', bridge, {
      onStart: () => order.push('listener.start'),
      onLevel: () => {},
      onStop: () => order.push('listener.stop'),
    })

    expect(order.filter(item => item === 'listener.start')).toHaveLength(1)
    expect(order.indexOf('listener.start')).toBeGreaterThan(order.indexOf('source.start'))
    expect(order.at(-1)).toBe('listener.stop')
  })

  it('does not emit a fake speaking lifecycle for an empty response', async () => {
    class FakeAudioContext {
      async resume() {}
      async close() {}
    }
    vi.stubGlobal('AudioContext', FakeAudioContext)
    const bridge = {
      prepareSpeech: async () => [],
    } as unknown as GahyeonDesktopBridge
    const events: string[] = []

    await new SpeechPlayer().speak('빈 응답', bridge, {
      onStart: () => events.push('start'),
      onLevel: () => {},
      onStop: () => events.push('stop'),
    })

    expect(events).toEqual([])
  })

  it('plays incrementally queued sentences as one speaking lifecycle', async () => {
    class FakeSource {
      onended: null | (() => void) = null
      buffer?: AudioBuffer
      connect() {}
      disconnect() {}
      stop() {}
      start() { queueMicrotask(() => this.onended?.()) }
    }
    class FakeAnalyser {
      fftSize = 256
      smoothingTimeConstant = 0
      connect() {}
      disconnect() {}
      getByteTimeDomainData(values: Uint8Array) { values.fill(128) }
    }
    class FakeAudioContext {
      destination = {}
      async resume() {}
      async close() {}
      async decodeAudioData() { return {} as AudioBuffer }
      createBufferSource() { return new FakeSource() }
      createAnalyser() { return new FakeAnalyser() }
    }
    vi.stubGlobal('AudioContext', FakeAudioContext)
    vi.stubGlobal('requestAnimationFrame', () => 1)
    vi.stubGlobal('cancelAnimationFrame', () => {})
    const prepared: string[] = []
    const bridge = {
      async prepareSpeech(text: string) {
        prepared.push(text)
        return [{ index: 0, text }]
      },
      async synthesizeSpeech() {
        return { data: new ArrayBuffer(8), mediaType: 'audio/wav' }
      },
    } as unknown as GahyeonDesktopBridge
    const lifecycle: string[] = []
    const sequence = new SpeechPlayer().beginSequence(bridge, {
      onStart: () => lifecycle.push('start'),
      onLevel: () => {},
      onStop: () => lifecycle.push('stop'),
    })

    await sequence.enqueue('첫 문장.')
    await sequence.enqueue('둘째 문장.')
    await sequence.finish()

    expect(prepared).toEqual(['첫 문장.', '둘째 문장.'])
    expect(lifecycle).toEqual(['start', 'stop'])
  })

  it('prepares only the next segment while the current segment is playing', async () => {
    const sources: FakeSource[] = []
    class FakeSource {
      onended: null | (() => void) = null
      buffer?: AudioBuffer
      connect() {}
      disconnect() {}
      stop() { this.onended?.() }
      start() { sources.push(this) }
      end() { this.onended?.() }
    }
    class FakeAnalyser {
      fftSize = 256
      smoothingTimeConstant = 0
      connect() {}
      disconnect() {}
      getByteTimeDomainData(values: Uint8Array) { values.fill(128) }
    }
    class FakeAudioContext {
      destination = {}
      async resume() {}
      async close() {}
      async decodeAudioData() { return {} as AudioBuffer }
      createBufferSource() { return new FakeSource() }
      createAnalyser() { return new FakeAnalyser() }
    }
    vi.stubGlobal('AudioContext', FakeAudioContext)
    vi.stubGlobal('requestAnimationFrame', () => 1)
    vi.stubGlobal('cancelAnimationFrame', () => {})
    const synthesized: number[] = []
    const bridge = {
      prepareSpeech: async () => [
        { index: 0, text: '첫 문장' },
        { index: 1, text: '둘째 문장' },
        { index: 2, text: '셋째 문장' },
      ],
      async synthesizeSpeech(segment: { index: number }) {
        synthesized.push(segment.index)
        return { data: new ArrayBuffer(8), mediaType: 'audio/wav' }
      },
    } as unknown as GahyeonDesktopBridge
    const sequence = new SpeechPlayer().beginSequence(bridge, {
      onStart() {}, onLevel() {}, onStop() {},
    })
    const playback = sequence.enqueue('세 문장')

    await vi.waitFor(() => expect(sources).toHaveLength(1))
    await vi.waitFor(() => expect(synthesized).toEqual([0, 1]))
    expect(synthesized).not.toContain(2)
    sources[0].end()
    await vi.waitFor(() => expect(sources).toHaveLength(2))
    await vi.waitFor(() => expect(synthesized).toEqual([0, 1, 2]))
    sources[1].end()
    await vi.waitFor(() => expect(sources).toHaveLength(3))
    sources[2].end()
    await playback
    await sequence.finish()
  })

  it('prefetches the next streamed sentence while the current sentence is playing', async () => {
    const sources: FakeSource[] = []
    class FakeSource {
      onended: null | (() => void) = null
      buffer?: AudioBuffer
      connect() {}
      disconnect() {}
      stop() { this.onended?.() }
      start() { sources.push(this) }
      end() { this.onended?.() }
    }
    class FakeAnalyser {
      fftSize = 256
      smoothingTimeConstant = 0
      connect() {}
      disconnect() {}
      getByteTimeDomainData(values: Uint8Array) { values.fill(128) }
    }
    class FakeAudioContext {
      destination = {}
      async resume() {}
      async close() {}
      async decodeAudioData() { return {} as AudioBuffer }
      createBufferSource() { return new FakeSource() }
      createAnalyser() { return new FakeAnalyser() }
    }
    vi.stubGlobal('AudioContext', FakeAudioContext)
    vi.stubGlobal('requestAnimationFrame', () => 1)
    vi.stubGlobal('cancelAnimationFrame', () => {})
    const synthesized: string[] = []
    const bridge = {
      prepareSpeech: async (text: string) => [{ index: 0, text }],
      async synthesizeSpeech(segment: { text: string }) {
        synthesized.push(segment.text)
        return { data: new ArrayBuffer(8), mediaType: 'audio/wav' }
      },
    } as unknown as GahyeonDesktopBridge
    const sequence = new SpeechPlayer().beginSequence(bridge, {
      onStart() {}, onLevel() {}, onStop() {},
    })
    const first = sequence.enqueue('첫 문장')
    const second = sequence.enqueue('둘째 문장')

    await vi.waitFor(() => expect(sources).toHaveLength(1))
    await vi.waitFor(() => expect(synthesized).toEqual(['첫 문장', '둘째 문장']))
    sources[0].end()
    await first
    await vi.waitFor(() => expect(sources).toHaveLength(2))
    sources[1].end()
    await second
    await sequence.finish()
  })

  it('fails closed instead of growing an unbounded streamed sentence queue', async () => {
    class FakeAudioContext {
      async resume() {}
      async close() {}
    }
    vi.stubGlobal('AudioContext', FakeAudioContext)
    let releasePreparation: (() => void) | undefined
    const preparation = new Promise<Array<{ index: number, text: string }>>(resolve => {
      releasePreparation = () => resolve([])
    })
    const bridge = {
      prepareSpeech: () => preparation,
    } as unknown as GahyeonDesktopBridge
    const sequence = new SpeechPlayer().beginSequence(bridge, {
      onStart() {}, onLevel() {}, onStop() {},
    })

    for (let index = 0; index < 65; index++) void sequence.enqueue(`문장 ${index}`)
    releasePreparation?.()
    await expect(sequence.finish()).rejects.toThrow('speech text queue capacity exceeded')
  })

  it('reports text-queue backpressure without waiting for the blocked playback tail', async () => {
    class FakeAudioContext {
      async resume() {}
      async close() {}
    }
    vi.stubGlobal('AudioContext', FakeAudioContext)
    let releasePreparation: (() => void) | undefined
    const preparation = new Promise<Array<{ index: number, text: string }>>(resolve => {
      releasePreparation = () => resolve([])
    })
    const bridge = {
      prepareSpeech: () => preparation,
    } as unknown as GahyeonDesktopBridge
    const sequence = new SpeechPlayer().beginSequence(bridge, {
      onStart() {}, onLevel() {}, onStop() {},
    })
    for (let index = 0; index < 64; index++) void sequence.enqueue(`문장 ${index}`)

    const overflow = sequence.enqueue('거절되어야 할 문장')
    await expect(Promise.race([
      overflow,
      new Promise<void>((_resolve, reject) => window.setTimeout(
        () => reject(new Error('backpressure was hidden behind the playback tail')),
        50,
      )),
    ])).rejects.toThrow('speech text queue capacity exceeded')

    releasePreparation?.()
    await expect(sequence.finish()).rejects.toThrow('speech text queue capacity exceeded')
  })

  it('aborts a pending transport request when the sequence is cancelled', async () => {
    class FakeAudioContext {
      async resume() {}
      async close() {}
    }
    vi.stubGlobal('AudioContext', FakeAudioContext)
    const controller = new AbortController()
    let preparationStarted = false
    const bridge = {
      prepareSpeech: () => {
        preparationStarted = true
        return new Promise((_resolve, reject) => controller.signal.addEventListener(
          'abort', () => reject(new DOMException('cancelled', 'AbortError')), { once: true },
        ))
      },
      cancelSpeechRequests: () => controller.abort(),
    } as unknown as GahyeonDesktopBridge
    const sequence = new SpeechPlayer().beginSequence(bridge, {
      onStart() {}, onLevel() {}, onStop() {},
    })
    const pending = sequence.enqueue('취소할 문장')
    await vi.waitFor(() => expect(preparationStarted).toBe(true))

    sequence.cancel()
    expect(controller.signal.aborted).toBe(true)
    await expect(pending).resolves.toBeUndefined()
  })

  it('confirms a barge-in stop from the source ended callback', async () => {
    class FakeSource {
      onended: null | ((event: Event) => void) = null
      buffer?: AudioBuffer
      connect() {}
      disconnect() {}
      start() {}
      stop() { queueMicrotask(() => this.onended?.(new Event('ended'))) }
    }
    class FakeAnalyser {
      fftSize = 256
      smoothingTimeConstant = 0
      connect() {}
      disconnect() {}
      getByteTimeDomainData(values: Uint8Array) { values.fill(128) }
    }
    class FakeAudioContext {
      destination = {}
      async resume() {}
      async close() {}
      async decodeAudioData() { return {} as AudioBuffer }
      createBufferSource() { return new FakeSource() }
      createAnalyser() { return new FakeAnalyser() }
    }
    vi.stubGlobal('AudioContext', FakeAudioContext)
    vi.stubGlobal('requestAnimationFrame', () => 1)
    vi.stubGlobal('cancelAnimationFrame', () => {})
    const cancelSpeechRequests = vi.fn()
    const bridge = {
      prepareSpeech: async () => [{ index: 0, text: '긴 문장' }],
      synthesizeSpeech: async () => ({ data: new ArrayBuffer(8), mediaType: 'audio/wav' }),
      cancelSpeechRequests,
    } as unknown as GahyeonDesktopBridge
    const player = new SpeechPlayer()
    const sequence = player.beginSequence(bridge, { onStart() {}, onLevel() {}, onStop() {} })
    void sequence.enqueue('긴 문장')
    await vi.waitFor(() => expect((player as unknown as { activeSource?: unknown }).activeSource).toBeDefined())

    await expect(player.stop()).resolves.toBe(true)
    expect(cancelSpeechRequests).toHaveBeenCalledOnce()
    await sequence.finish()
  })
})
