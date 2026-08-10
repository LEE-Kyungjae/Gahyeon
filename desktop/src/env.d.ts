import type { GahyeonDesktopBridge } from './gahyeon-api'

declare global {
  interface Window {
    gahyeon: GahyeonDesktopBridge
  }
}

export {}
