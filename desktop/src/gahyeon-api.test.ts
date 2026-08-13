import { afterEach, describe, expect, it, vi } from 'vitest'
import { browserBridge } from './gahyeon-api'

describe('browser conversation transport', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('aborts the active POST before cancelling the Core generation', async () => {
    let conversationSignal: AbortSignal | undefined
    const fetchMock = vi.fn()
      .mockImplementationOnce((_input: unknown, init: RequestInit) => {
        conversationSignal = init.signal as AbortSignal
        return new Promise<Response>((_resolve, reject) => {
          conversationSignal!.addEventListener(
            'abort', () => reject(conversationSignal!.reason), { once: true },
          )
        })
      })
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    const pending = browserBridge.sendMessage({
      sessionId: 'session-1', requestId: 'request-1', installationId: 'install-1',
      displayName: 'Tester', message: '안녕',
    })
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await browserBridge.cancelConversation('session-1', 'install-1')

    expect(conversationSignal?.aborted).toBe(true)
    await rejected
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(String(fetchMock.mock.calls[1][0])).toContain('installationId=install-1')
  })

  it('never consumes an account link code in the browser fallback', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    await expect(browserBridge.linkDesktop({
      code: 'secret-code', installationId: 'install-1', displayName: 'Tester',
    })).rejects.toMatchObject({ code: 'identityLink', detail: 'nativeDesktopRequired' })
    await expect(browserBridge.getIdentityLinkStatus('install-1'))
      .resolves.toEqual({ linked: false })
    await expect(browserBridge.unlinkCurrentDesktop('install-1'))
      .rejects.toMatchObject({ code: 'identityLink', detail: 'nativeDesktopRequired' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('reports an arrived world action with its revision and exact position', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ result: 'COMMITTED' }),
      { status: 200, headers: { 'content-type': 'application/json' } },
    ))
    vi.stubGlobal('fetch', fetchMock)

    await expect(browserBridge.completeWorldAction('gahyeon-home', {
      installationId: 'install-1',
      actionId: 'action/18',
      expectedRevision: 7,
      finalPosition: { x: 4.5, y: 0, z: -2.25 },
    })).resolves.toEqual({ result: 'COMMITTED' })

    expect(String(fetchMock.mock.calls[0][0]))
      .toContain('/worlds/gahyeon-home/actions/action%2F18/complete')
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      installationId: 'install-1', expectedRevision: 7, x: 4.5, y: 0, z: -2.25,
    })
  })

  it('heartbeats and releases exact world-scoped renderer presence', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await browserBridge.heartbeatWorldPresence('gahyeon-home', 'install-1')
    await browserBridge.releaseWorldPresence('gahyeon-home', 'install-1')

    expect(String(fetchMock.mock.calls[0][0])).toContain('/worlds/gahyeon-home/presence')
    const heartbeat = JSON.parse(String(fetchMock.mock.calls[0][1]?.body)) as {
      installationId: string
      rendererId: string
    }
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'POST' })
    expect(heartbeat.installationId).toBe('install-1')
    expect(heartbeat.rendererId).toMatch(/^[0-9a-f-]+$/i)
    expect(heartbeat.rendererId.length).toBeLessThanOrEqual(120)
    expect(String(fetchMock.mock.calls[1][0])).toContain('installationId=install-1')
    expect(String(fetchMock.mock.calls[1][0]))
      .toContain(`rendererId=${encodeURIComponent(heartbeat.rendererId)}`)
    expect(fetchMock.mock.calls[1][1]).toMatchObject({ method: 'DELETE' })
  })

  it('subscribes to the exact world as well as the conversation session', () => {
    let sourceUrl = ''
    class FakeEventSource {
      onerror?: () => void
      constructor(url: string) { sourceUrl = url }
      addEventListener() {}
      close() {}
    }
    vi.stubGlobal('EventSource', FakeEventSource)

    const unsubscribe = browserBridge.subscribeEvents({
      sessionId: 'session-1', installationId: 'install-1',
      worldId: 'gahyeon-home', afterSequence: 7,
    }, () => undefined)

    expect(sourceUrl).toContain('worldId=gahyeon-home')
    expect(sourceUrl).toContain('sessionId=session-1')
    unsubscribe()
  })
})
