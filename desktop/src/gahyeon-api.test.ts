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
})
