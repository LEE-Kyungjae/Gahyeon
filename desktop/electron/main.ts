import {
  app,
  BrowserWindow,
  ipcMain,
  safeStorage,
  type IpcMainEvent,
  type IpcMainInvokeEvent,
} from 'electron'
import { dirname, join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { mkdirSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs'
import { readBoundedArrayBuffer } from './bounded-response.js'
import { admitDurableEventId, isDesktopPresentationEvent, parseEventStream } from './sse.js'
import { abortableDelay, ReconnectBackoff } from './reconnect-backoff.js'
import { SpeechRequestRegistry } from './speech-request-registry.js'
import { resolveWindowPreset } from './window-preset.js'
import { resolveNativeGlass } from './native-glass.js'
import {
  CONTROLS_GLASS_HEIGHT,
  CONTROLS_GLASS_WIDTH,
  controlsGlassBounds,
  roundedCapsuleShape,
} from './controls-glass-surface.js'
import {
  isTrustedIpcSender,
  isTrustedRendererLocation,
  validateAudioInput,
  validateConversationCancellation,
  validateConversationExpressionPlan,
  validateEventSubscription,
  validateIdentityLinkRequest,
  validateInstallationId,
  validateMessageRequest,
  validateRendererId,
  validateSpeechSegment,
  validateSpeechText,
  validateWorldActionCompletion,
  validateWorldId,
  type TrustedRendererIdentity,
} from './ipc-security.js'

const apiBaseUrl = process.env.GAHYEON_CORE_API_URL ?? 'http://127.0.0.1:8080/api'
const clientToken = process.env.GAHYEON_CLIENT_TOKEN ?? ''
let accountCredential = ''
const subscriptions = new Map<number, AbortController>()
const trustedRenderers = new Map<number, TrustedRendererIdentity>()
const speechRequests = new SpeechRequestRegistry()
const conversationRequests = new SpeechRequestRegistry()
const CONVERSATION_TIMEOUT_MILLIS = 10_000
const METADATA_TIMEOUT_MILLIS = 5_000
const MAXIMUM_SPEECH_AUDIO_BYTES = 16 * 1024 * 1024
let characterWindow: BrowserWindow | undefined
let controlsGlassWindow: BrowserWindow | undefined
let characterControlsWindow: BrowserWindow | undefined
let chatWindow: BrowserWindow | undefined

function syncControlsGlassBounds() {
  if (!characterWindow || characterWindow.isDestroyed() || !controlsGlassWindow || controlsGlassWindow.isDestroyed()) return
  controlsGlassWindow.setBounds(controlsGlassBounds(characterWindow.getBounds()))
}

function createControlsGlassWindow(owner: BrowserWindow, nativeGlass: ReturnType<typeof resolveNativeGlass>) {
  if (!nativeGlass.enabled) return
  const glass = new BrowserWindow({
    width: CONTROLS_GLASS_WIDTH,
    height: CONTROLS_GLASS_HEIGHT,
    frame: false,
    transparent: true,
    backgroundColor: '#00000000',
    resizable: false,
    movable: false,
    focusable: false,
    skipTaskbar: true,
    show: false,
    hasShadow: false,
    ...nativeGlass.options,
  })
  controlsGlassWindow = glass
  glass.setIgnoreMouseEvents(true)
  if (process.platform === 'win32') {
    glass.setShape(roundedCapsuleShape(CONTROLS_GLASS_WIDTH, CONTROLS_GLASS_HEIGHT))
  }
  void glass.loadURL('data:text/html,<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:rgba(255,255,255,.01);border-radius:999px}</style>')
  syncControlsGlassBounds()
  owner.on('move', syncControlsGlassBounds)
  owner.on('resize', syncControlsGlassBounds)
  owner.once('closed', () => {
    if (!glass.isDestroyed()) glass.destroy()
    if (controlsGlassWindow === glass) controlsGlassWindow = undefined
    if (characterWindow === owner) characterWindow = undefined
  })
}

function coreHeaders(headers: Record<string, string> = {}) {
  const authenticated = deploymentHeaders(headers)
  return accountCredential
    ? { ...authenticated, 'x-gahyeon-account-token': accountCredential }
    : authenticated
}

function deploymentHeaders(headers: Record<string, string> = {}) {
  return clientToken
    ? { ...headers, authorization: `Bearer ${clientToken}` }
    : { ...headers }
}

function credentialPath() { return join(app.getPath('userData'), 'desktop-account-credential.bin') }

function loadAccountCredential() {
  try {
    if (safeStorage.isEncryptionAvailable()) {
      accountCredential = safeStorage.decryptString(readFileSync(credentialPath()))
    }
  } catch { accountCredential = '' }
}

function storeAccountCredential(value: string) {
  if (!safeStorage.isEncryptionAvailable()) throw new Error('OS credential encryption is unavailable')
  const target = credentialPath()
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, safeStorage.encryptString(value), { mode: 0o600 })
  accountCredential = value
}

function clearAccountCredential() {
  accountCredential = ''
  try { unlinkSync(credentialPath()) } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error
  }
}

function createWindow() {
  const preset = resolveWindowPreset(process.env)
  const nativeGlass = resolveNativeGlass(process.platform, process.env, preset.name === 'character')
  const window = new BrowserWindow({
    ...preset.options,
    webPreferences: {
      // Sandboxed Electron preload scripts are loaded as CommonJS even when the
      // application package uses ESM. TypeScript emits preload.cts as preload.cjs.
      preload: join(import.meta.dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })
  window.setAlwaysOnTop(preset.alwaysOnTop)
  window.setIgnoreMouseEvents(preset.clickThrough, { forward: true })
  if (preset.name === 'character') {
    characterWindow = window
    createControlsGlassWindow(window, nativeGlass)
    if (process.env.GAHYEON_DESKTOP_OPEN_CONTROLS === 'true') {
      window.webContents.once('did-finish-load', openCharacterControlsWindow)
    }
  }

  const devUrl = process.env.GAHYEON_DESKTOP_DEV_URL
  const productionEntry = join(import.meta.dirname, '../dist/index.html')
  const entry = devUrl ? new URL(devUrl) : pathToFileURL(productionEntry)
  entry.searchParams.set('gahyeonWindowPreset', preset.name)
  const entryUrl = entry.href
  loadTrustedWindow(window, entryUrl)
}

function loadTrustedWindow(window: BrowserWindow, entryUrl: string) {
  const identity = { senderId: window.webContents.id, entryUrl }
  trustedRenderers.set(identity.senderId, identity)
  const preventUntrustedNavigation = (event: Electron.Event, destination: string) => {
    if (!isTrustedRendererLocation(destination, entryUrl)) event.preventDefault()
  }
  window.webContents.on('will-navigate', preventUntrustedNavigation)
  window.webContents.on('will-redirect', preventUntrustedNavigation)
  window.webContents.on('will-attach-webview', event => event.preventDefault())
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  window.webContents.once('destroyed', () => {
    trustedRenderers.delete(identity.senderId)
    subscriptions.get(identity.senderId)?.abort()
    subscriptions.delete(identity.senderId)
    speechRequests.cancel(identity.senderId)
    conversationRequests.cancel(identity.senderId)
  })
  void window.loadURL(entryUrl)
}

function auxiliaryEntry(surface: 'controls' | 'chat') {
  const devUrl = process.env.GAHYEON_DESKTOP_DEV_URL
  const productionEntry = join(import.meta.dirname, '../dist/index.html')
  const entry = devUrl ? new URL(devUrl) : pathToFileURL(productionEntry)
  entry.searchParams.set('gahyeonSurface', surface)
  return entry.href
}

function openCharacterControlsWindow() {
  if (characterControlsWindow && !characterControlsWindow.isDestroyed()) {
    characterControlsWindow.show()
    characterControlsWindow.focus()
    return
  }
  const ownerBounds = characterWindow?.getBounds()
  // WSLg/XWayland does not reliably route pointer input into a fully
  // transparent frameless Electron surface. Use a conventional input surface
  // on Linux; macOS and native Windows keep the glass presentation.
  const reliableLinuxInput = process.platform === 'linux'
  const window = new BrowserWindow({
    width: 292,
    height: 224,
    x: ownerBounds ? ownerBounds.x + ownerBounds.width - 310 : undefined,
    y: ownerBounds ? ownerBounds.y + ownerBounds.height - 246 : undefined,
    frame: reliableLinuxInput,
    transparent: !reliableLinuxInput,
    backgroundColor: reliableLinuxInput ? '#172131' : '#00000000',
    resizable: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    focusable: true,
    webPreferences: {
      preload: join(import.meta.dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })
  window.setIgnoreMouseEvents(false)
  characterControlsWindow = window
  window.once('closed', () => { if (characterControlsWindow === window) characterControlsWindow = undefined })
  loadTrustedWindow(window, auxiliaryEntry('controls'))
}

function openChatWindow() {
  if (chatWindow && !chatWindow.isDestroyed()) {
    chatWindow.show()
    chatWindow.focus()
    return
  }
  const window = new BrowserWindow({
    width: 440,
    height: 680,
    minWidth: 360,
    minHeight: 520,
    title: '가현 채팅',
    backgroundColor: '#15171f',
    webPreferences: {
      preload: join(import.meta.dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })
  chatWindow = window
  window.once('closed', () => { if (chatWindow === window) chatWindow = undefined })
  loadTrustedWindow(window, auxiliaryEntry('chat'))
}

ipcMain.handle('gahyeon:message', async (event, rawRequest: unknown) => {
  requireTrustedIpcEvent(event)
  const request = validatePayload(() => validateMessageRequest(rawRequest))
  const active = conversationRequests.begin(event.sender.id)
  try {
    const response = await fetchWithTimeout(
      `${apiBaseUrl}/gahyeon/desktop/conversations/${encodeURIComponent(request.sessionId)}/messages`,
      {
        method: 'POST',
        headers: coreHeaders({ 'content-type': 'application/json' }),
        body: JSON.stringify(request),
      },
      CONVERSATION_TIMEOUT_MILLIS,
      'conversation',
      active.signal,
    )
    if (!response.ok) throw clientError('conversation', response.status)
    return response.json()
  }
  finally {
    active.complete()
  }
})

ipcMain.handle('gahyeon:identity:link', async (event, rawRequest: unknown) => {
  requireTrustedIpcEvent(event)
  const request = validatePayload(() => validateIdentityLinkRequest(rawRequest))
  const response = await fetchWithTimeout(`${apiBaseUrl}/gahyeon/desktop/identity/link`, {
    method: 'POST',
    // Recovery must remain possible after Discord revokes the credential currently
    // stored on this device, so the one-time link exchange uses deployment auth only.
    headers: deploymentHeaders({ 'content-type': 'application/json' }),
    body: JSON.stringify(request),
  }, METADATA_TIMEOUT_MILLIS, 'identityLink', AbortSignal.timeout(METADATA_TIMEOUT_MILLIS))
  if (!response.ok) throw clientError('identityLink', response.status)
  const linked = await response.json() as { linked: boolean, credential: string }
  if (!linked.linked || !linked.credential) throw clientError('identityLink', 502)
  storeAccountCredential(linked.credential)
  return { linked: true }
})

ipcMain.handle('gahyeon:identity:status', async (event, rawInstallationId: unknown) => {
  requireTrustedIpcEvent(event)
  const installationId = validatePayload(() => validateInstallationId(rawInstallationId))
  const response = await fetchWithTimeout(
    `${apiBaseUrl}/gahyeon/desktop/identity/status?installationId=${encodeURIComponent(installationId)}`,
    { headers: coreHeaders() }, METADATA_TIMEOUT_MILLIS, 'identityLink',
    AbortSignal.timeout(METADATA_TIMEOUT_MILLIS),
  )
  if (!response.ok) throw clientError('identityLink', response.status)
  return response.json()
})

ipcMain.handle('gahyeon:identity:unlink', async (event, rawInstallationId: unknown) => {
  requireTrustedIpcEvent(event)
  const installationId = validatePayload(() => validateInstallationId(rawInstallationId))
  if (!accountCredential) throw clientError('identityLink', 401)
  const response = await fetchWithTimeout(
    `${apiBaseUrl}/gahyeon/desktop/identity/current?installationId=${encodeURIComponent(installationId)}`,
    { method: 'DELETE', headers: coreHeaders() }, METADATA_TIMEOUT_MILLIS, 'identityLink',
    AbortSignal.timeout(METADATA_TIMEOUT_MILLIS),
  )
  if (!response.ok) throw clientError('identityLink', response.status)
  clearAccountCredential()
})

ipcMain.handle('gahyeon:conversation:cancel', async (
  event,
  rawSessionId: unknown,
  rawInstallationId: unknown,
) => {
  requireTrustedIpcEvent(event)
  const { sessionId, installationId } = validatePayload(
    () => validateConversationCancellation(rawSessionId, rawInstallationId),
  )
  conversationRequests.cancel(event.sender.id)
  const response = await fetchWithTimeout(
    `${apiBaseUrl}/gahyeon/desktop/conversations/${encodeURIComponent(sessionId)}/active?installationId=${encodeURIComponent(installationId)}`,
    { method: 'DELETE', headers: coreHeaders() }, 5_000, 'conversationCancel',
    AbortSignal.timeout(5_000),
  )
  if (!response.ok) throw clientError('conversationCancel', response.status)
})

ipcMain.handle('gahyeon:world:get', async (event, rawWorldId: unknown) => {
  requireTrustedIpcEvent(event)
  const worldId = validatePayload(() => validateWorldId(rawWorldId))
  const response = await fetchWithTimeout(
    `${apiBaseUrl}/gahyeon/desktop/worlds/${encodeURIComponent(worldId)}`,
    { headers: coreHeaders() }, METADATA_TIMEOUT_MILLIS, 'world',
    AbortSignal.timeout(METADATA_TIMEOUT_MILLIS),
  )
  if (!response.ok) throw clientError('world', response.status)
  return response.json()
})

ipcMain.handle('gahyeon:world:action:complete', async (
  event,
  rawWorldId: unknown,
  rawRequest: unknown,
) => {
  requireTrustedIpcEvent(event)
  const { worldId, request } = validatePayload(
    () => validateWorldActionCompletion(rawWorldId, rawRequest),
  )
  const response = await fetchWithTimeout(
    `${apiBaseUrl}/gahyeon/desktop/worlds/${encodeURIComponent(worldId)}/actions/${encodeURIComponent(request.actionId)}/complete`,
    {
      method: 'POST',
      headers: coreHeaders({ 'content-type': 'application/json' }),
      body: JSON.stringify({
        installationId: request.installationId,
        expectedRevision: request.expectedRevision,
        x: request.finalPosition.x,
        y: request.finalPosition.y,
        z: request.finalPosition.z,
      }),
    },
    METADATA_TIMEOUT_MILLIS,
    'worldActionCompletion',
    AbortSignal.timeout(METADATA_TIMEOUT_MILLIS),
  )
  if (!response.ok) throw clientError('worldActionCompletion', response.status)
  return response.json()
})

ipcMain.handle('gahyeon:world:presence:heartbeat', async (
  event,
  rawWorldId: unknown,
  rawInstallationId: unknown,
  rawRendererId: unknown,
) => {
  requireTrustedIpcEvent(event)
  const worldId = validatePayload(() => validateWorldId(rawWorldId))
  const installationId = validatePayload(() => validateInstallationId(rawInstallationId))
  const rendererId = validatePayload(() => validateRendererId(rawRendererId))
  const response = await fetchWithTimeout(
    `${apiBaseUrl}/gahyeon/desktop/worlds/${encodeURIComponent(worldId)}/presence`,
    {
      method: 'POST',
      headers: coreHeaders({ 'content-type': 'application/json' }),
      body: JSON.stringify({ installationId, rendererId }),
    },
    METADATA_TIMEOUT_MILLIS,
    'worldPresence',
    AbortSignal.timeout(METADATA_TIMEOUT_MILLIS),
  )
  if (!response.ok) throw clientError('worldPresence', response.status)
})

ipcMain.handle('gahyeon:world:presence:release', async (
  event,
  rawWorldId: unknown,
  rawInstallationId: unknown,
  rawRendererId: unknown,
) => {
  requireTrustedIpcEvent(event)
  const worldId = validatePayload(() => validateWorldId(rawWorldId))
  const installationId = validatePayload(() => validateInstallationId(rawInstallationId))
  const rendererId = validatePayload(() => validateRendererId(rawRendererId))
  const url = new URL(
    `${apiBaseUrl}/gahyeon/desktop/worlds/${encodeURIComponent(worldId)}/presence`,
  )
  url.searchParams.set('installationId', installationId)
  url.searchParams.set('rendererId', rendererId)
  const response = await fetchWithTimeout(
    url,
    { method: 'DELETE', headers: coreHeaders() },
    METADATA_TIMEOUT_MILLIS,
    'worldPresence',
    AbortSignal.timeout(METADATA_TIMEOUT_MILLIS),
  )
  if (!response.ok) throw clientError('worldPresence', response.status)
})

ipcMain.handle('gahyeon:speech:status', async (event) => {
  requireTrustedIpcEvent(event)
  const response = await fetchWithTimeout(
    `${apiBaseUrl}/gahyeon/desktop/speech/status`,
    { headers: coreHeaders() }, METADATA_TIMEOUT_MILLIS, 'speechStatus',
    AbortSignal.timeout(METADATA_TIMEOUT_MILLIS),
  )
  if (!response.ok) throw clientError('speechStatus', response.status)
  return response.json()
})

ipcMain.handle('gahyeon:speech:expression-plan', async (event, rawRequest: unknown) => {
  requireTrustedIpcEvent(event)
  const request = validatePayload(() => validateConversationExpressionPlan(rawRequest))
  const response = await fetchWithTimeout(
    `${apiBaseUrl}/gahyeon/desktop/speech/expression-plans`,
    {
      method: 'POST',
      headers: coreHeaders({ 'content-type': 'application/json' }),
      body: JSON.stringify(request),
    },
    METADATA_TIMEOUT_MILLIS,
    'speechExpressionPlan',
    AbortSignal.timeout(METADATA_TIMEOUT_MILLIS),
  )
  if (!response.ok) throw clientError('speechExpressionPlan', response.status)
  return response.json()
})

ipcMain.handle('gahyeon:speech:transcribe', async (event, rawAudio: unknown) => {
  requireTrustedIpcEvent(event)
  const audio = validatePayload(() => validateAudioInput(rawAudio))
  return withSpeechRequest(event.sender.id, async signal => {
    const response = await fetchWithTimeout(`${apiBaseUrl}/gahyeon/desktop/speech/transcriptions`, {
      method: 'POST',
      headers: coreHeaders({ 'content-type': 'audio/wav' }),
      body: Buffer.from(audio),
    }, 10_000, 'transcription', signal)
    if (!response.ok) throw clientError('transcription', response.status)
    const body = await response.json() as { transcript: string }
    return body.transcript
  })
})

ipcMain.handle('gahyeon:speech:prepare', async (event, rawText: unknown) => {
  requireTrustedIpcEvent(event)
  const speechText = validatePayload(() => validateSpeechText(rawText))
  return withSpeechRequest(event.sender.id, async signal => {
    const response = await fetchWithTimeout(`${apiBaseUrl}/gahyeon/desktop/speech/segments`, {
      method: 'POST',
      headers: coreHeaders({ 'content-type': 'application/json' }),
      body: JSON.stringify({ text: speechText }),
    }, METADATA_TIMEOUT_MILLIS, 'speechSegments', signal)
    if (!response.ok) throw clientError('speechSegments', response.status)
    return response.json()
  })
})

ipcMain.handle('gahyeon:speech:synthesize', async (event, rawSegment: unknown) => {
  requireTrustedIpcEvent(event)
  const segment = validatePayload(() => validateSpeechSegment(rawSegment))
  return withSpeechRequest(event.sender.id, async signal => {
    const response = await fetchWithTimeout(`${apiBaseUrl}/gahyeon/desktop/speech/synthesis`, {
      method: 'POST',
      headers: coreHeaders({ 'content-type': 'application/json' }),
      body: JSON.stringify(segment),
    }, 25_000, 'synthesis', signal)
    if (!response.ok) throw clientError('synthesis', response.status)
    return {
      data: await readBoundedArrayBuffer(
        response,
        MAXIMUM_SPEECH_AUDIO_BYTES,
        () => clientError('synthesis', 'responseTooLarge'),
      ),
      mediaType: response.headers.get('content-type') ?? 'audio/wav',
    }
  })
})

ipcMain.on('gahyeon:speech:cancel', (event) => {
  if (!isTrustedIpcEvent(event)) return
  speechRequests.cancel(event.sender.id)
})

ipcMain.on('gahyeon:controls-glass', (event, expanded: unknown) => {
  if (!isTrustedIpcEvent(event) || typeof expanded !== 'boolean') return
  if (!characterWindow || event.sender.id !== characterWindow.webContents.id) return
  const glass = controlsGlassWindow
  if (!glass || glass.isDestroyed()) return
  if (!expanded) {
    glass.hide()
    return
  }
  syncControlsGlassBounds()
  glass.showInactive()
  glass.moveTop()
  characterWindow.moveTop()
})

ipcMain.on('gahyeon:window:controls', event => {
  if (!isTrustedIpcEvent(event)) return
  openCharacterControlsWindow()
})

ipcMain.on('gahyeon:window:chat', event => {
  if (!isTrustedIpcEvent(event)) return
  openChatWindow()
})

ipcMain.on('gahyeon:window:close-current', event => {
  if (!isTrustedIpcEvent(event)) return
  BrowserWindow.fromWebContents(event.sender)?.close()
})

ipcMain.on('gahyeon:window:close-character', event => {
  if (!isTrustedIpcEvent(event)) return
  characterWindow?.close()
  characterControlsWindow?.close()
})

function clientError(code: string, detail: string | number) {
  return new Error(`GAHYEON_CLIENT_ERROR:${code}:${detail}`)
}

function requireTrustedIpcEvent(event: IpcMainEvent | IpcMainInvokeEvent) {
  if (!isTrustedIpcEvent(event)) throw clientError('ipc', 'untrustedRenderer')
}

function isTrustedIpcEvent(event: IpcMainEvent | IpcMainInvokeEvent) {
  const frame = event.senderFrame
  const trusted = trustedRenderers.get(event.sender.id)
  return Boolean(frame && isTrustedIpcSender({
    senderId: event.sender.id,
    mainFrame: frame === event.sender.mainFrame,
    url: frame.url,
  }, trusted))
}

function validatePayload<T>(validator: () => T) {
  try {
    return validator()
  }
  catch {
    throw clientError('ipc', 'invalidPayload')
  }
}

async function fetchWithTimeout(
  input: string | URL,
  init: RequestInit,
  timeoutMs: number,
  code: string,
  cancellation: AbortSignal,
) {
  try {
    return await fetch(input, {
      ...init,
      signal: AbortSignal.any([cancellation, AbortSignal.timeout(timeoutMs)]),
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'TimeoutError') {
      throw clientError(code, 'timeout')
    }
    throw error
  }
}

async function withSpeechRequest<T>(
  senderId: number,
  operation: (signal: AbortSignal) => Promise<T>,
) {
  const request = speechRequests.begin(senderId)
  try {
    return await operation(request.signal)
  } finally {
    request.complete()
  }
}

ipcMain.on('gahyeon:events:subscribe', (ipcEvent, rawRequest: unknown) => {
  if (!isTrustedIpcEvent(ipcEvent)) return
  let request
  try {
    request = validateEventSubscription(rawRequest)
  }
  catch {
    return
  }
  subscriptions.get(ipcEvent.sender.id)?.abort()
  const controller = new AbortController()
  subscriptions.set(ipcEvent.sender.id, controller)
  void consumeEvents(ipcEvent.sender, request, controller)
})

ipcMain.on('gahyeon:events:unsubscribe', (event) => {
  if (!isTrustedIpcEvent(event)) return
  subscriptions.get(event.sender.id)?.abort()
  subscriptions.delete(event.sender.id)
})

async function consumeEvents(
  sender: Electron.WebContents,
  request: {
    sessionId: string
    installationId: string
    worldId: string
    afterSequence: number
  },
  controller: AbortController,
) {
  let cursor = Number.isSafeInteger(request.afterSequence) && request.afterSequence >= 0
    ? request.afterSequence
    : 0
  const backoff = new ReconnectBackoff()
  while (!controller.signal.aborted && !sender.isDestroyed()) {
    let delivered = false
    try {
      const url = new URL(`${apiBaseUrl}/gahyeon/desktop/events`)
      url.searchParams.set('sessionId', request.sessionId)
      url.searchParams.set('installationId', request.installationId)
      url.searchParams.set('worldId', request.worldId)
      url.searchParams.set('afterSequence', String(cursor))
      const response = await fetch(url, {
        headers: coreHeaders({ accept: 'text/event-stream' }),
        signal: controller.signal,
      })
      if (!response.ok || !response.body) throw clientError('eventStream', response.status)
      for await (const event of parseEventStream(response.body)) {
        // A reconnect commonly repeats the current durable cursor. Connection
        // health is ephemeral and must not be discarded as a duplicate event.
        if (event.event === 'stream.connected') {
          sender.send('gahyeon:event', event)
          delivered = true
          continue
        }
        const admission = admitDurableEventId(cursor, event.id)
        if (!admission.accepted) continue
        if (isDesktopPresentationEvent(event.event)) sender.send('gahyeon:event', event)
        cursor = admission.cursor
        delivered = true
      }
    }
    catch (error) {
      if (controller.signal.aborted) break
    }
    if (controller.signal.aborted || sender.isDestroyed()) break
    sender.send('gahyeon:event', {
      event: 'stream.error',
      data: { code: 'eventStream' },
    })
    if (delivered) backoff.reset()
    await abortableDelay(backoff.nextDelayMs(), controller.signal)
  }
}

app.whenReady().then(() => {
  loadAccountCredential()
  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  subscriptions.forEach(controller => controller.abort())
  speechRequests.cancelAll()
  conversationRequests.cancelAll()
  if (process.platform !== 'darwin') app.quit()
})
