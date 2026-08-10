import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('gahyeon', {
  sendMessage: (request: unknown) => ipcRenderer.invoke('gahyeon:message', request),
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
