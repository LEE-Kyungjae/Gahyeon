import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('gahyeon', {
  sendMessage: (request: unknown) => ipcRenderer.invoke('gahyeon:message', request),
  getWorldState: (worldId: string) => ipcRenderer.invoke('gahyeon:world:get', worldId),
  getSpeechStatus: () => ipcRenderer.invoke('gahyeon:speech:status'),
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
