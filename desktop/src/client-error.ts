import { t, type MessageKey } from './i18n'

const marker = 'GAHYEON_CLIENT_ERROR:'

export class GahyeonClientError extends Error {
  constructor(readonly code: string, readonly detail = '') {
    super(`${marker}${code}:${detail}`)
    this.name = 'GahyeonClientError'
  }
}

const translations: Record<string, MessageKey> = {
  conversation: 'error.conversation', conversationCancel: 'error.conversation',
  responseLimit: 'error.responseLimit',
  world: 'error.world', speechStatus: 'error.speechStatus',
  transcription: 'error.transcription', speechSegments: 'error.speechSegments', synthesis: 'error.synthesis',
  eventStream: 'error.eventStream', recorderInactive: 'error.recorderInactive', recordingShort: 'error.recordingShort',
  vrmInvalid: 'error.vrmInvalid', vrmaManifest: 'error.vrmaManifest', vrmaClip: 'error.vrmaClip',
  heroManifest: 'error.heroManifest',
  identityLink: 'error.identityLink',
}

export function localizedError(error: unknown): string {
  const raw = error instanceof Error ? error.message : String(error)
  const position = raw.indexOf(marker)
  if (position < 0) return raw
  const encoded = raw.slice(position + marker.length)
  const separator = encoded.indexOf(':')
  const code = separator < 0 ? encoded : encoded.slice(0, separator)
  const detail = separator < 0 ? '' : encoded.slice(separator + 1)
  const key = translations[code]
  return key ? t(key, { details: detail }) : t('error.unknown', { details: detail || code })
}
