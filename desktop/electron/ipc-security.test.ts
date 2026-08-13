import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import {
  isTrustedRendererLocation,
  isTrustedIpcSender,
  validateAudioInput,
  validateEventSubscription,
  validateMessageRequest,
  validateRendererId,
  validateWorldActionCompletion,
} from './ipc-security.js'

describe('Electron IPC security boundary', () => {
  it('keeps every privileged IPC channel behind the trusted-renderer gate', () => {
    const source = readFileSync(new URL('./main.ts', import.meta.url), 'utf8')
    const handlers = source.matchAll(/ipcMain\.handle\([^]*?\n}\)/g)
    const blocks = [...handlers].map(match => match[0])
    expect(blocks.length).toBe(13)
    expect(blocks.every(block => block.includes('requireTrustedIpcEvent(event)'))).toBe(true)
    expect(source).toContain("ipcMain.on('gahyeon:speech:cancel'")
    expect(source).toContain("ipcMain.on('gahyeon:events:subscribe'")
    expect(source).toContain("ipcMain.on('gahyeon:events:unsubscribe'")
    expect(source).toContain("url.searchParams.set('worldId', request.worldId)")
    expect(source).toContain("ipcMain.handle('gahyeon:world:presence:heartbeat'")
    expect(source).toContain("ipcMain.handle('gahyeon:world:presence:release'")
    expect(source).toContain("body: JSON.stringify({ installationId, rendererId })")
    expect(source).toContain("url.searchParams.set('rendererId', rendererId)")
    const preload = readFileSync(new URL('./preload.ts', import.meta.url), 'utf8')
    expect(preload).toContain('const rendererId = crypto.randomUUID()')
    expect(preload).toMatch(
      /ipcRenderer\.invoke\(\s*'gahyeon:world:presence:heartbeat', worldId, installationId, rendererId/,
    )
    expect(preload).toMatch(
      /ipcRenderer\.invoke\(\s*'gahyeon:world:presence:release', worldId, installationId, rendererId/,
    )
    expect(source.match(/if \(!isTrustedIpcEvent\([^)]*\)\) return/g)).toHaveLength(3)
    expect(source).toContain("window.webContents.on('will-navigate'")
    expect(source).toContain("window.webContents.on('will-redirect'")
    expect(source).toContain("window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))")
  })

  it('admits only the configured renderer document and ignores query/hash', () => {
    const trusted = 'file:///Applications/Gahyeon/resources/app.asar/dist/index.html'
    expect(isTrustedRendererLocation(
      `${trusted}?locale=ko#conversation`, trusted,
    )).toBe(true)
    expect(isTrustedRendererLocation(
      'https://attacker.invalid/index.html', trusted,
    )).toBe(false)
    expect(isTrustedRendererLocation(
      'file:///Applications/Gahyeon/resources/app.asar/dist/other.html', trusted,
    )).toBe(false)
    expect(isTrustedRendererLocation('not a URL', trusted)).toBe(false)
    expect(isTrustedIpcSender(
      { senderId: 42, mainFrame: true, url: trusted },
      { senderId: 42, entryUrl: trusted },
    )).toBe(true)
    expect(isTrustedIpcSender(
      { senderId: 42, mainFrame: false, url: trusted },
      { senderId: 42, entryUrl: trusted },
    )).toBe(false)
    expect(isTrustedIpcSender(
      { senderId: 99, mainFrame: true, url: trusted },
      { senderId: 42, entryUrl: trusted },
    )).toBe(false)
  })

  it('validates and bounds conversation and event subscription payloads', () => {
    expect(validateMessageRequest({
      sessionId: 'desktop-1', requestId: 'request-1', installationId: 'install-1',
      displayName: '가현', message: '안녕',
    })).toMatchObject({ sessionId: 'desktop-1', message: '안녕' })
    expect(() => validateMessageRequest({
      sessionId: 'desktop-1', requestId: 'request-1', installationId: 'install-1',
      displayName: '가현', message: 'x'.repeat(16_385),
    })).toThrow('message')
    expect(() => validateEventSubscription({
      sessionId: 'desktop-1', installationId: 'install-1', worldId: 'gahyeon-home',
      afterSequence: -1,
    })).toThrow('afterSequence')
    expect(validateEventSubscription({
      sessionId: 'desktop-1', installationId: 'install-1', worldId: 'gahyeon-home',
      afterSequence: 0,
    }).worldId).toBe('gahyeon-home')
    expect(() => validateEventSubscription({
      sessionId: 's'.repeat(181), installationId: 'install-1', afterSequence: 0,
    })).toThrow('sessionId')
  })

  it('rejects malformed, non-finite, and oversized action/audio payloads', () => {
    expect(validateRendererId('550e8400-e29b-41d4-a716-446655440000'))
      .toBe('550e8400-e29b-41d4-a716-446655440000')
    expect(() => validateRendererId(' ')).toThrow('rendererId')
    expect(() => validateRendererId('r'.repeat(121))).toThrow('rendererId')
    expect(validateWorldActionCompletion('gahyeon-home', {
      installationId: 'install-1', actionId: 'action-1', expectedRevision: 7,
      finalPosition: { x: 1, y: 0, z: -2 },
    }).request.finalPosition).toEqual({ x: 1, y: 0, z: -2 })
    expect(() => validateWorldActionCompletion('gahyeon-home', {
      installationId: 'install-1', actionId: 'action-1', expectedRevision: 7,
      finalPosition: { x: Number.NaN, y: 0, z: -2 },
    })).toThrow('finalPosition.x')
    expect(() => validateWorldActionCompletion('gahyeon-home', {
      installationId: 'install-1', actionId: 'a'.repeat(81), expectedRevision: 7,
      finalPosition: { x: 1, y: 0, z: -2 },
    })).toThrow('actionId')
    expect(() => validateAudioInput(new ArrayBuffer(20 * 1024 * 1024 + 1)))
      .toThrow('audio')
    expect(() => validateAudioInput(new Uint8Array([1, 2, 3])))
      .toThrow('audio')
  })
})
