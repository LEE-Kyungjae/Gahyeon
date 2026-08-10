import { app, BrowserWindow, ipcMain } from 'electron'
import { join } from 'node:path'
import { parseEventStream } from './sse.js'

const apiBaseUrl = process.env.GAHYEON_CORE_API_URL ?? 'http://127.0.0.1:8080/api'
const clientToken = process.env.GAHYEON_CLIENT_TOKEN ?? ''
const subscriptions = new Map<number, AbortController>()

function coreHeaders(headers: Record<string, string> = {}) {
  return clientToken
    ? { ...headers, authorization: `Bearer ${clientToken}` }
    : headers
}

function createWindow() {
  const window = new BrowserWindow({
    width: 1180,
    height: 760,
    minWidth: 820,
    minHeight: 560,
    backgroundColor: '#101218',
    title: 'Gahyeon',
    webPreferences: {
      preload: join(import.meta.dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })

  const devUrl = process.env.GAHYEON_DESKTOP_DEV_URL
  if (devUrl) void window.loadURL(devUrl)
  else void window.loadFile(join(import.meta.dirname, '../dist/index.html'))
}

ipcMain.handle('gahyeon:message', async (_event, request: {
  sessionId: string
  requestId: string
  installationId: string
  displayName: string
  message: string
}) => {
  const response = await fetch(
    `${apiBaseUrl}/gahyeon/desktop/conversations/${encodeURIComponent(request.sessionId)}/messages`,
    {
      method: 'POST',
      headers: coreHeaders({ 'content-type': 'application/json' }),
      body: JSON.stringify(request),
    },
  )
  if (!response.ok) throw new Error(`Gahyeon Core 응답 오류 (${response.status})`)
  return response.json()
})

ipcMain.handle('gahyeon:world:get', async (_event, worldId: string) => {
  const response = await fetch(
    `${apiBaseUrl}/gahyeon/desktop/worlds/${encodeURIComponent(worldId)}`,
    { headers: coreHeaders() },
  )
  if (!response.ok) throw new Error(`World State 응답 오류 (${response.status})`)
  return response.json()
})

ipcMain.handle('gahyeon:speech:status', async () => {
  const response = await fetch(`${apiBaseUrl}/gahyeon/desktop/speech/status`, {
    headers: coreHeaders(),
  })
  if (!response.ok) throw new Error(`Speech 상태 응답 오류 (${response.status})`)
  return response.json()
})

ipcMain.handle('gahyeon:speech:transcribe', async (_event, audio: ArrayBuffer) => {
  const response = await fetch(`${apiBaseUrl}/gahyeon/desktop/speech/transcriptions`, {
    method: 'POST',
    headers: coreHeaders({ 'content-type': 'audio/wav' }),
    body: Buffer.from(audio),
  })
  if (!response.ok) throw new Error(`STT 응답 오류 (${response.status})`)
  const body = await response.json() as { transcript: string }
  return body.transcript
})

ipcMain.handle('gahyeon:speech:prepare', async (_event, text: string) => {
  const response = await fetch(`${apiBaseUrl}/gahyeon/desktop/speech/segments`, {
    method: 'POST',
    headers: coreHeaders({ 'content-type': 'application/json' }),
    body: JSON.stringify({ text }),
  })
  if (!response.ok) throw new Error(`TTS 분할 응답 오류 (${response.status})`)
  return response.json()
})

ipcMain.handle('gahyeon:speech:synthesize', async (_event, segment: { index: number, text: string }) => {
  const response = await fetch(`${apiBaseUrl}/gahyeon/desktop/speech/synthesis`, {
    method: 'POST',
    headers: coreHeaders({ 'content-type': 'application/json' }),
    body: JSON.stringify({ ...segment, voiceProfile: 'gahyeon.assistant' }),
  })
  if (!response.ok) throw new Error(`TTS 응답 오류 (${response.status})`)
  return {
    data: await response.arrayBuffer(),
    mediaType: response.headers.get('content-type') ?? 'audio/wav',
  }
})

ipcMain.on('gahyeon:events:subscribe', (ipcEvent, request: {
  sessionId: string
  afterSequence: number
}) => {
  subscriptions.get(ipcEvent.sender.id)?.abort()
  const controller = new AbortController()
  subscriptions.set(ipcEvent.sender.id, controller)
  void consumeEvents(ipcEvent.sender, request, controller)
})

ipcMain.on('gahyeon:events:unsubscribe', (event) => {
  subscriptions.get(event.sender.id)?.abort()
  subscriptions.delete(event.sender.id)
})

async function consumeEvents(
  sender: Electron.WebContents,
  request: { sessionId: string, afterSequence: number },
  controller: AbortController,
) {
  let cursor = request.afterSequence
  while (!controller.signal.aborted && !sender.isDestroyed()) {
    try {
      const url = new URL(`${apiBaseUrl}/gahyeon/desktop/events`)
      url.searchParams.set('sessionId', request.sessionId)
      url.searchParams.set('afterSequence', String(cursor))
      const response = await fetch(url, {
        headers: coreHeaders({ accept: 'text/event-stream' }),
        signal: controller.signal,
      })
      if (!response.ok || !response.body) throw new Error(`Event stream 오류 (${response.status})`)
      for await (const event of parseEventStream(response.body)) {
        if (event.id) cursor = Number(event.id)
        sender.send('gahyeon:event', event)
      }
    }
    catch (error) {
      if (controller.signal.aborted) break
      sender.send('gahyeon:event', {
        event: 'stream.error',
        data: { message: error instanceof Error ? error.message : String(error) },
      })
      await new Promise(resolve => setTimeout(resolve, 1_000))
    }
  }
}

app.whenReady().then(() => {
  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  subscriptions.forEach(controller => controller.abort())
  if (process.platform !== 'darwin') app.quit()
})
