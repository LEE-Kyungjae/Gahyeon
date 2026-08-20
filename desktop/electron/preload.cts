import { contextBridge, ipcRenderer } from 'electron'

const rendererId = crypto.randomUUID()

contextBridge.exposeInMainWorld('gahyeon', {
  sendMessage: (request: unknown) => ipcRenderer.invoke('gahyeon:message', request),
  linkDesktop: (request: unknown) => ipcRenderer.invoke('gahyeon:identity:link', request),
  getIdentityLinkStatus: (installationId: string) => ipcRenderer.invoke('gahyeon:identity:status', installationId),
  unlinkCurrentDesktop: (installationId: string) => ipcRenderer.invoke('gahyeon:identity:unlink', installationId),
  cancelConversation: (sessionId: string, installationId: string) => ipcRenderer.invoke('gahyeon:conversation:cancel', sessionId, installationId),
  cancelSpeechRequests: () => ipcRenderer.send('gahyeon:speech:cancel'),
  setControlsGlassExpanded: (expanded: boolean) =>
    ipcRenderer.send('gahyeon:controls-glass', expanded),
  openControlsPanel: () => ipcRenderer.send('gahyeon:window:controls'),
  openChatWindow: () => ipcRenderer.send('gahyeon:window:chat'),
  closeCurrentWindow: () => ipcRenderer.send('gahyeon:window:close-current'),
  closeCharacterWindow: () => ipcRenderer.send('gahyeon:window:close-character'),
  getWorldState: (worldId: string) => ipcRenderer.invoke('gahyeon:world:get', worldId),
  completeWorldAction: (worldId: string, request: unknown) =>
    ipcRenderer.invoke('gahyeon:world:action:complete', worldId, request),
  heartbeatWorldPresence: (worldId: string, installationId: string) =>
    ipcRenderer.invoke(
      'gahyeon:world:presence:heartbeat', worldId, installationId, rendererId,
    ),
  releaseWorldPresence: (worldId: string, installationId: string) =>
    ipcRenderer.invoke(
      'gahyeon:world:presence:release', worldId, installationId, rendererId,
    ),
  getSpeechStatus: () => ipcRenderer.invoke('gahyeon:speech:status'),
  planConversationExpression: (request: unknown) =>
    ipcRenderer.invoke('gahyeon:speech:expression-plan', request),
  transcribeWav: (audio: ArrayBuffer) => ipcRenderer.invoke('gahyeon:speech:transcribe', audio),
  prepareSpeech: (text: string) => ipcRenderer.invoke('gahyeon:speech:prepare', text),
  synthesizeSpeech: (segment: unknown) => ipcRenderer.invoke('gahyeon:speech:synthesize', segment),
  subscribeEvents: (request: unknown, listener: (event: unknown) => void) => {
    const handler = (_event: Electron.IpcRendererEvent, payload: unknown) => listener(payload)
    ipcRenderer.on('gahyeon:event', handler)
    ipcRenderer.send('gahyeon:events:subscribe', request)
    return () => {
      ipcRenderer.removeListener('gahyeon:event', handler)
      ipcRenderer.send('gahyeon:events:unsubscribe')
    }
  },
})
