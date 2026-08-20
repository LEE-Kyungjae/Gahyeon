const MAXIMUM_SESSION_ID_CHARACTERS = 180
const MAXIMUM_REQUEST_ID_CHARACTERS = 120
const MAXIMUM_INSTALLATION_ID_CHARACTERS = 200
const MAXIMUM_DISPLAY_NAME_CHARACTERS = 100
const MAXIMUM_MESSAGE_CHARACTERS = 16_384
const MAXIMUM_LINK_CODE_CHARACTERS = 128
const MAXIMUM_WORLD_ID_CHARACTERS = 120
const MAXIMUM_RENDERER_ID_CHARACTERS = 120
const MAXIMUM_ACTION_ID_CHARACTERS = 80
const MAXIMUM_CHARACTER_ID_CHARACTERS = 64
const MAXIMUM_AUDIO_BYTES = 20 * 1024 * 1024
const MAXIMUM_SPEECH_TEXT_CHARACTERS = 16_384
const MAXIMUM_SEGMENT_INDEX = 1_000_000
const MAXIMUM_WORLD_COORDINATE = 1_000_000

export interface TrustedRendererIdentity {
  senderId: number
  entryUrl: string
}

export interface IpcSenderIdentity {
  senderId: number
  mainFrame: boolean
  url: string
}

export interface ValidatedMessageRequest {
  sessionId: string
  requestId: string
  installationId: string
  displayName: string
  characterId: string
  message: string
}

export interface ValidatedIdentityLinkRequest {
  code: string
  installationId: string
  displayName: string
}

export interface ValidatedWorldActionCompletion {
  worldId: string
  request: {
    installationId: string
    actionId: string
    expectedRevision: number
    finalPosition: { x: number, y: number, z: number }
  }
}

export interface ValidatedEventSubscription {
  sessionId: string
  installationId: string
  worldId: string
  afterSequence: number
}

/** Exact-document allowlist. Query and hash are renderer-local state only. */
export function isTrustedRendererLocation(candidate: string, entryUrl: string) {
  try {
    const actual = new URL(candidate)
    const trusted = new URL(entryUrl)
    return actual.protocol === trusted.protocol
      && actual.username === trusted.username
      && actual.password === trusted.password
      && actual.host === trusted.host
      && actual.pathname === trusted.pathname
  }
  catch {
    return false
  }
}

export function isTrustedIpcSender(
  sender: IpcSenderIdentity,
  trusted: TrustedRendererIdentity | undefined,
) {
  return trusted !== undefined
    && sender.senderId === trusted.senderId
    && sender.mainFrame
    && isTrustedRendererLocation(sender.url, trusted.entryUrl)
}

export function validateMessageRequest(value: unknown): ValidatedMessageRequest {
  const request = record(value, 'request')
  return {
    sessionId: boundedText(request.sessionId, 'sessionId', MAXIMUM_SESSION_ID_CHARACTERS),
    requestId: boundedText(request.requestId, 'requestId', MAXIMUM_REQUEST_ID_CHARACTERS),
    installationId: boundedText(
      request.installationId, 'installationId', MAXIMUM_INSTALLATION_ID_CHARACTERS,
    ),
    displayName: boundedText(
      request.displayName, 'displayName', MAXIMUM_DISPLAY_NAME_CHARACTERS, true,
    ),
    characterId: safeSlug(request.characterId ?? 'gahyeon', 'characterId', MAXIMUM_CHARACTER_ID_CHARACTERS),
    message: boundedText(request.message, 'message', MAXIMUM_MESSAGE_CHARACTERS),
  }
}

export function validateIdentityLinkRequest(value: unknown): ValidatedIdentityLinkRequest {
  const request = record(value, 'request')
  return {
    code: boundedText(request.code, 'code', MAXIMUM_LINK_CODE_CHARACTERS),
    installationId: validateInstallationId(request.installationId),
    displayName: boundedText(
      request.displayName, 'displayName', MAXIMUM_DISPLAY_NAME_CHARACTERS, true,
    ),
  }
}

export function validateInstallationId(value: unknown) {
  return boundedText(value, 'installationId', MAXIMUM_INSTALLATION_ID_CHARACTERS)
}

export function validateConversationCancellation(
  sessionId: unknown,
  installationId: unknown,
) {
  return {
    sessionId: boundedText(sessionId, 'sessionId', MAXIMUM_SESSION_ID_CHARACTERS),
    installationId: validateInstallationId(installationId),
  }
}

export function validateWorldId(value: unknown) {
  return boundedText(value, 'worldId', MAXIMUM_WORLD_ID_CHARACTERS)
}

export function validateRendererId(value: unknown) {
  return boundedText(value, 'rendererId', MAXIMUM_RENDERER_ID_CHARACTERS)
}

export function validateWorldActionCompletion(
  worldIdValue: unknown,
  requestValue: unknown,
): ValidatedWorldActionCompletion {
  const request = record(requestValue, 'request')
  const position = record(request.finalPosition, 'finalPosition')
  return {
    worldId: validateWorldId(worldIdValue),
    request: {
      installationId: validateInstallationId(request.installationId),
      actionId: boundedText(request.actionId, 'actionId', MAXIMUM_ACTION_ID_CHARACTERS),
      expectedRevision: safeInteger(request.expectedRevision, 'expectedRevision'),
      finalPosition: {
        x: coordinate(position.x, 'finalPosition.x'),
        y: coordinate(position.y, 'finalPosition.y'),
        z: coordinate(position.z, 'finalPosition.z'),
      },
    },
  }
}

export function validateAudioInput(value: unknown) {
  if (!(value instanceof ArrayBuffer)
      || value.byteLength < 1 || value.byteLength > MAXIMUM_AUDIO_BYTES) {
    invalid('audio')
  }
  return value
}

export function validateSpeechText(value: unknown) {
  return boundedText(value, 'text', MAXIMUM_SPEECH_TEXT_CHARACTERS)
}

export function validateConversationExpressionPlan(value: unknown) {
  const request = record(value, 'expressionPlan')
  return {
    installationId: validateInstallationId(request.installationId),
    displayName: boundedText(
      request.displayName, 'displayName', MAXIMUM_DISPLAY_NAME_CHARACTERS, true,
    ),
    characterId: safeSlug(request.characterId, 'characterId', MAXIMUM_CHARACTER_ID_CHARACTERS),
    worldId: validateWorldId(request.worldId),
    message: boundedText(request.message, 'message', MAXIMUM_MESSAGE_CHARACTERS),
  }
}

export function validateSpeechSegment(value: unknown) {
  const segment = record(value, 'segment')
  const index = safeInteger(segment.index, 'index')
  if (index > MAXIMUM_SEGMENT_INDEX) invalid('index')
  const expression = segment.expression === undefined
    ? undefined
    : validateVoiceExpression(segment.expression)
  return {
    index,
    text: validateSpeechText(segment.text),
    voiceProfile: safeSlug(segment.voiceProfile ?? 'gahyeon.assistant', 'voiceProfile', 64),
    ...(expression ? { expression } : {}),
  }
}

function validateVoiceExpression(value: unknown) {
  const expression = record(value, 'expression')
  const style = safeSlug(expression.style, 'expression.style', 40)
  const allowed = new Set([
    'natural', 'warm', 'gentle', 'bright', 'surprised', 'concerned', 'serious',
    'playful', 'fake_cute', 'sarcastic', 'sleepy', 'whisper', 'excited',
    'annoyed', 'sad', 'suppressed_laugh',
  ])
  if (!allowed.has(style)) invalid('expression.style')
  if (typeof expression.intensity !== 'number' || !Number.isFinite(expression.intensity)
      || expression.intensity < 0 || expression.intensity > 1) invalid('expression.intensity')
  return {
    style,
    intensity: expression.intensity,
    communicativeIntent: boundedText(
      expression.communicativeIntent, 'expression.communicativeIntent', 80,
    ),
  }
}

export function validateEventSubscription(value: unknown): ValidatedEventSubscription {
  const request = record(value, 'request')
  return {
    sessionId: boundedText(request.sessionId, 'sessionId', MAXIMUM_SESSION_ID_CHARACTERS),
    installationId: validateInstallationId(request.installationId),
    worldId: validateWorldId(request.worldId),
    afterSequence: safeInteger(request.afterSequence, 'afterSequence'),
  }
}

function record(value: unknown, field: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid(field)
  return value as Record<string, unknown>
}

function safeSlug(value: unknown, field: string, maximumCharacters: number) {
  const text = boundedText(value, field, maximumCharacters)
  if (!/^[a-z0-9][a-z0-9._-]*$/.test(text)) invalid(field)
  return text
}

function boundedText(
  value: unknown,
  field: string,
  maximumCharacters: number,
  allowBlank = false,
) {
  if (typeof value !== 'string' || value.length > maximumCharacters
      || (!allowBlank && value.trim().length === 0)) invalid(field)
  return value
}

function safeInteger(value: unknown, field: string) {
  if (!Number.isSafeInteger(value) || (value as number) < 0) invalid(field)
  return value as number
}

function coordinate(value: unknown, field: string) {
  if (typeof value !== 'number' || !Number.isFinite(value)
      || Math.abs(value) > MAXIMUM_WORLD_COORDINATE) invalid(field)
  return value
}

function invalid(field: string): never {
  throw new Error(`Invalid IPC payload: ${field}`)
}
