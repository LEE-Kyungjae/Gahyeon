<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { getGahyeonBridge } from './gahyeon-api'
import { SpeechPlayer } from './audio/speech-player'
import type { SpeechSequence } from './audio/speech-player'
import { IncrementalSentenceAccumulator, unseenFinalText } from './conversation/incremental-sentence-accumulator'
import {
  isUnsuccessfulConversationTerminal,
  parseConversationTerminal,
  reconcileConversationTerminal,
} from './conversation/terminal-event'
import { trimTimeline } from './conversation/bounded-timeline'
import { RecentRequestRegistry } from './conversation/recent-request-registry'
import {
  appendBoundedResponseText,
  MAXIMUM_CONVERSATION_RESPONSE_CHARACTERS,
} from './conversation/bounded-response-text'
import { WavRecorder } from './audio/wav-recorder'
import { initialStageState, reduceStageEvent } from './stage/stage-state'
import type { PendingWorldAction } from './stage/stage-state'
import { stageExpressionForSemantic, stageExpressionPayload } from './stage/voice-expression'
import { locale, setLocale, t, type Locale, type MessageKey } from './i18n'
import { GahyeonClientError, localizedError } from './client-error'
import { LatencyMetrics } from './runtime/latency-metrics'
import { DurableEventCursor, type CursorAdmission } from './runtime/durable-event-cursor'
import { isEventVisibleToWorld } from './runtime/world-event-admission'
import { WorldPresenceLease } from './runtime/world-presence-lease'
import { WorldActionAckOutbox } from './runtime/world-action-ack-outbox'
import { WorldActionAckWorker } from './runtime/world-action-ack-worker'
import { worldSnapshotEvent } from './runtime/world-snapshot-admission'
import { terminalActionResultId } from './runtime/world-action-result'
import { WorldActionAckCoordinator } from './runtime/world-action-ack-coordinator'
import { PendingWorldActionJournal } from './runtime/pending-world-action-journal'
import { parseAutonomousCognition } from './runtime/autonomous-cognition'
import {
  characterActivityMediaUrl,
  desktopCharacter,
  desktopCharacters,
  DESKTOP_CHARACTER_STORAGE_KEY,
  restoreDesktopCharacter,
  type DesktopCharacterId,
} from './character-catalog'

const StageView = defineAsyncComponent(() => import('./components/StageView.vue'))

interface ChatEntry {
  id: string
  role: 'user' | 'gahyeon' | 'system'
  text: string
  messageKey?: MessageKey
  requestId?: string
}

const installationId = persistentId('gahyeon.installationId', 'installation')
const sessionId = persistentId('gahyeon.sessionId', 'desktop')
const displayName = ref(localStorage.getItem('gahyeon.displayName') ?? t('identity.defaultName'))
const input = ref('')
const sending = ref(false)
const activeRequestId = ref<string>()
const recording = ref(false)
const microphoneEnabled = ref(false)
const transcribing = ref(false)
const transcriptionReady = ref(false)
const synthesisReady = ref(false)
const expressiveSynthesisReady = ref(false)
const autonomousSpeaking = ref(false)
const voiceOutput = ref(localStorage.getItem('gahyeon.voiceOutput') !== 'false')
const controlsExpanded = ref(false)
const identityLinkCode = ref('')
const identityLinking = ref(false)
const identityLinked = ref(false)
const identityCredentialExpiresAt = ref<string>()
const nativeDesktop = window.gahyeon !== undefined
const streamState = ref<'connecting' | 'connected' | 'error'>('connecting')
const completedRequests = new RecentRequestRegistry()
const unsuccessfulRequests = new RecentRequestRegistry()
const worldId = 'gahyeon-home'
const pendingWorldActions = new PendingWorldActionJournal(localStorage)
const restoredWorldAction = pendingWorldActions.restore(worldId)
const worldStageReady = ref(!restoredWorldAction.restored)
const pendingConversations = new Map<string, {
  sentences: IncrementalSentenceAccumulator
  speech?: SpeechSequence
  receivedText: string
  admittedAt: number
  firstDeltaRecorded: boolean
}>()
const messages = ref<ChatEntry[]>([
  { id: 'welcome', role: 'gahyeon', text: '', messageKey: 'conversation.welcome' },
])
const stageState = ref(restoredWorldAction.restored
  ? { ...initialStageState, pendingWorldAction: restoredWorldAction.action }
  : initialStageState)
const messageList = ref<HTMLElement>()
const durableEvents = new DurableEventCursor(
  localStorage.getItem(`gahyeon.cursor.${sessionId}`),
)
let unsubscribe: (() => void) | undefined
let worldDurabilityFailed = !pendingWorldActions.health().healthy
const gahyeon = getGahyeonBridge()
const modelUrl = import.meta.env.VITE_GAHYEON_VRM_URL as string | undefined
const heroManifestUrl = import.meta.env.VITE_GAHYEON_HERO_MANIFEST_URL as string | undefined
const animationManifestUrl = import.meta.env.VITE_GAHYEON_VRMA_MANIFEST as string | undefined
const worldUrl = import.meta.env.VITE_GAHYEON_WORLD_URL as string | undefined
const lookingGlassEnabled = import.meta.env.VITE_GAHYEON_LOOKING_GLASS === 'true'
const characterWindow = new URLSearchParams(window.location.search)
  .get('gahyeonWindowPreset') === 'character'
const chatWindow = new URLSearchParams(window.location.search)
  .get('gahyeonSurface') === 'chat'
const selectedCharacterId = ref(restoreDesktopCharacter(localStorage))
const selectedCharacter = computed(() => desktopCharacter(selectedCharacterId.value))
const characterMediaUrl = computed(() => characterWindow
  ? characterActivityMediaUrl(
      selectedCharacter.value,
      window.location.href,
      stageState.value.activity,
      import.meta.env.VITE_GAHYEON_CHARACTER_MEDIA_URL as string | undefined,
    )
  : undefined)
const worldPresence = new WorldPresenceLease(gahyeon, worldId, installationId)
const worldActionAcks = new WorldActionAckCoordinator(
  new WorldActionAckWorker(new WorldActionAckOutbox(localStorage), gahyeon),
  worldPresence,
)
if (worldDurabilityFailed) worldActionAcks.failDurability()
const recorder = new WavRecorder()
const speechPlayer = new SpeechPlayer()
const latencyMetrics = new LatencyMetrics()
const MAXIMUM_TIMELINE_ENTRIES = 500
window.gahyeonRuntimeDiagnostics = Object.freeze({
  latencySnapshot: () => latencyMetrics.snapshot(),
})
let recordingTimeout: number | undefined
let transcriptionGeneration = 0

const statusLabel = computed(() => ({
  connecting: t('status.connecting'), connected: t('status.connected'), error: t('status.error'),
})[streamState.value])
const receivingStream = computed(() => messages.value.some(message => message.requestId !== undefined))
const identityExpiryWarning = computed(() => {
  if (!identityCredentialExpiresAt.value) return undefined
  const expiry = Date.parse(identityCredentialExpiresAt.value)
  if (!Number.isFinite(expiry)) return undefined
  const remainingDays = Math.ceil((expiry - Date.now()) / 86_400_000)
  return remainingDays <= 7
    ? t('identity.expiryWarning', { date: new Date(expiry).toLocaleDateString(locale.value) })
    : undefined
})
const characterVoiceLabel = computed(() => {
  if (transcribing.value) return 'STT…'
  if (stageState.value.speaking) return t('role.gahyeon')
  if (sending.value) return 'THINKING…'
  return microphoneEnabled.value ? t('voice.microphoneOff') : t('voice.microphoneOn')
})

function changeLocale(event: Event) {
  const previousDefaultName = t('identity.defaultName')
  setLocale((event.target as HTMLSelectElement).value as Locale)
  if (displayName.value === previousDefaultName) displayName.value = t('identity.defaultName')
}

async function linkIdentity() {
  const code = identityLinkCode.value.trim()
  if (!code || identityLinking.value) return
  identityLinking.value = true
  try {
    await gahyeon.linkDesktop({ code, installationId, displayName: displayName.value })
    await loadIdentityLinkStatus()
    identityLinkCode.value = ''
    addSystemMessage(t('identity.linkSuccess'))
  } catch (error) {
    addSystemMessage(localizedError(error))
  } finally {
    identityLinking.value = false
  }
}

async function unlinkIdentity() {
  if (identityLinking.value || !nativeDesktop) return
  identityLinking.value = true
  try {
    await gahyeon.unlinkCurrentDesktop(installationId)
    identityLinked.value = false
    identityCredentialExpiresAt.value = undefined
    addSystemMessage(t('identity.unlinkSuccess'))
  } catch (error) {
    addSystemMessage(localizedError(error))
  } finally {
    identityLinking.value = false
  }
}

onMounted(() => {
  window.addEventListener('storage', restoreSelectedCharacter)
  worldActionAcks.start()
  void loadIdentityLinkStatus()
  subscribeToCoreEvents()
  void loadSpeechStatus()
  void loadWorldSnapshot()
})

async function loadIdentityLinkStatus() {
  try {
    const status = await gahyeon.getIdentityLinkStatus(installationId)
    identityLinked.value = status.linked
    identityCredentialExpiresAt.value = status.credentialExpiresAt ?? undefined
  } catch {
    identityLinked.value = false
    identityCredentialExpiresAt.value = undefined
  }
}

async function loadSpeechStatus() {
  try {
    const speech = await gahyeon.getSpeechStatus()
    transcriptionReady.value = speech.transcriptionReady
    synthesisReady.value = speech.synthesisReady
    expressiveSynthesisReady.value = speech.expressiveSynthesisReady
  }
  catch {
    transcriptionReady.value = false
    synthesisReady.value = false
    expressiveSynthesisReady.value = false
    streamState.value = 'error'
  }
}

async function loadWorldSnapshot() {
  try {
    const snapshot = await gahyeon.getWorldState(worldId)
    const event = worldSnapshotEvent(snapshot, worldId)
    if (event) {
      const previous = stageState.value
      const next = reduceStageEvent(previous, event)
      if (!clearSupersededWorldActions(previous, next)) {
        failWorldDurability()
        return
      }
      stageState.value = next
      worldStageReady.value = true
    }
  }
  catch {
    // The local stage remains alive with its default world. Event-stream
    // health is owned only by stream.connected/stream.error callbacks.
  }
}

function completeWorldAction(action: PendingWorldAction) {
  if (!worldActionAcks.enqueue({
    worldId,
    request: {
      installationId,
      actionId: action.actionId,
      expectedRevision: action.expectedRevision,
      finalPosition: action.position,
    },
  })) return
  // The durable ACK outbox now owns recovery. Clear the pre-arrival journal
  // only after that synchronous handoff has succeeded, so a crash never loses
  // the action between navigation and acknowledgement delivery.
  if (!pendingWorldActions.clear(action.actionId).persisted) {
    failWorldDurability()
  }
}

function subscribeToCoreEvents() {
  unsubscribe = gahyeon.subscribeEvents({
    sessionId,
    installationId,
    worldId,
    afterSequence: durableEvents.current(),
  }, event => {
    const cursorAdmission = durableEvents.prepare(event)
    if (!cursorAdmission) return
    if (!isEventVisibleToWorld(event, worldId)) {
      tryPersistDurableEventCursor(cursorAdmission)
      return
    }
    const previousStageState = stageState.value
    const nextStageState = reduceStageEvent(previousStageState, event)
    if (event.event === 'world.transition.target' && nextStageState !== previousStageState) {
      const action = nextStageState.pendingWorldAction !== previousStageState.pendingWorldAction
        ? nextStageState.pendingWorldAction
        : nextStageState.deferredWorldAction !== previousStageState.deferredWorldAction
          ? nextStageState.deferredWorldAction
          : undefined
      if (action && !pendingWorldActions.record(worldId, action).persisted) {
        stageState.value = nextStageState
        failWorldDurability()
        return
      }
    }
    if (event.event === 'character.action.result') {
      const actionId = terminalActionResultId(event.data)
      if (actionId) {
        if (!pendingWorldActions.clear(actionId).persisted) {
          stageState.value = nextStageState
          failWorldDurability()
          return
        }
        worldActionAcks.reconcile(actionId)
        if (restoredWorldAction.restored
            && restoredWorldAction.action.actionId === actionId) {
          worldStageReady.value = true
        }
      }
    }
    if (!clearSupersededWorldActions(previousStageState, nextStageState)) {
      stageState.value = nextStageState
      failWorldDurability()
      return
    }
    stageState.value = nextStageState
    if (restoredWorldAction.restored
        && (event.event === 'world.state.changed' || event.event === 'world.state.restored')) {
      worldStageReady.value = true
    }
    if (event.event === 'stream.connected') streamState.value = 'connected'
    if (event.event === 'stream.error') streamState.value = 'connecting'
    if (event.event === 'conversation.delta') applyConversationDelta(event.data)
    if (event.event === 'character.cognition.completed') {
      void playAutonomousCognition(event.data)
    }
    applyConversationTerminal(event.event, event.data)
    tryPersistDurableEventCursor(cursorAdmission)
  })
}

async function playAutonomousCognition(data: unknown) {
  const cognition = parseAutonomousCognition(data)
  if (!cognition || cognition.characterId !== selectedCharacterId.value) return
  if (!voiceOutput.value || !expressiveSynthesisReady.value || autonomousSpeaking.value) return
  if (sending.value || recording.value || transcribing.value || stageState.value.speaking) return
  autonomousSpeaking.value = true
  const previousPresentation = {
    activity: stageState.value.activity,
    expression: stageState.value.expression,
    intensity: stageState.value.expressionIntensity,
    gazeTarget: stageState.value.gazeTarget,
    gesture: stageState.value.gesture,
  }
  appendMessage({ id: crypto.randomUUID(), role: 'gahyeon', text: cognition.utterance })
  try {
    applySpeechEvent('avatar.presentation', {
      expression: stageExpressionForSemantic(cognition.facialExpression, cognition.expression.style),
      intensity: cognition.expression.intensity,
      gazeTarget: cognition.gazeTarget,
      gesture: cognition.gesture,
    })
    await speechPlayer.speakExpressive(
      cognition.utterance,
      gahyeon,
      speechListener(performance.now()),
      cognition.voiceProfile,
      cognition.expression,
    )
  }
  finally {
    applySpeechEvent('avatar.presentation', cognition.resumePreviousActivity
      ? previousPresentation
      : { expression: 'neutral', intensity: 0, gazeTarget: 'ambient', gesture: 'none' })
    autonomousSpeaking.value = false
  }
}

function tryPersistDurableEventCursor(admission: CursorAdmission) {
  if (!admission.durable || worldDurabilityFailed) return
  try {
    localStorage.setItem(`gahyeon.cursor.${sessionId}`, String(admission.cursor))
  }
  catch {
    failWorldDurability()
    return
  }
  durableEvents.commit(admission)
}

function failWorldDurability() {
  if (worldDurabilityFailed) return
  worldDurabilityFailed = true
  worldActionAcks.failDurability()
  unsubscribe?.()
  unsubscribe = undefined
}

function clearSupersededWorldActions(previous: typeof initialStageState, next: typeof initialStageState) {
  const retained = new Set([
    next.pendingWorldAction?.actionId,
    next.deferredWorldAction?.actionId,
  ])
  const previousActions = [previous.pendingWorldAction, previous.deferredWorldAction]
  for (const action of previousActions) {
    if (!action || retained.has(action.actionId)) continue
    if (!pendingWorldActions.clear(action.actionId).persisted) return false
  }
  return true
}

onBeforeUnmount(() => {
  window.removeEventListener('storage', restoreSelectedCharacter)
  microphoneEnabled.value = false
  unsubscribe?.()
  worldActionAcks.stop()
  gahyeon.cancelSpeechRequests()
  if (recordingTimeout !== undefined) window.clearTimeout(recordingTimeout)
  void recorder.cancel()
  void speechPlayer.dispose()
})

function setRendererPresence(present: boolean) {
  worldActionAcks.setRendererPresent(present)
}

async function send(fromTranscription = false) {
  const text = input.value.trim()
  if (!text) return
  if (!fromTranscription && transcribing.value) cancelTranscription()
  localStorage.setItem('gahyeon.displayName', displayName.value)
  input.value = ''
  const requestId = crypto.randomUUID()
  const admittedAt = performance.now()
  activeRequestId.value = requestId
  sending.value = true
  stopSpeechForBargeIn()
  applyPresentationEvent('conversation.started')
  appendMessage({ id: crypto.randomUUID(), role: 'user', text })
  await scrollToEnd()
  let conversationExpression
  if (voiceOutput.value && expressiveSynthesisReady.value) {
    try {
      const planned = await gahyeon.planConversationExpression({
        installationId,
        displayName: displayName.value,
        characterId: selectedCharacterId.value,
        worldId,
        message: text,
      })
      // Natural keeps the low-latency default voice. Only a validated non-neutral
      // plan opts into the slower expressive worker.
      if (planned.style !== 'natural') {
        conversationExpression = planned
        applySpeechEvent('avatar.expression', stageExpressionPayload(planned))
      }
    }
    catch {
      conversationExpression = undefined
    }
  }
  const pending = {
    sentences: new IncrementalSentenceAccumulator(),
    speech: voiceOutput.value && synthesisReady.value
      ? speechPlayer.beginSequence(
          gahyeon,
          speechListener(admittedAt),
          selectedCharacter.value.voiceProfile,
          conversationExpression,
        )
      : undefined,
    receivedText: '',
    admittedAt,
    firstDeltaRecorded: false,
  }
  pendingConversations.set(requestId, pending)
  try {
    const response = await gahyeon.sendMessage({
      sessionId,
      requestId,
      installationId,
      displayName: displayName.value,
      characterId: selectedCharacterId.value,
      message: text,
    })
    if (unsuccessfulRequests.has(requestId)) return
    if (response.content.length > MAXIMUM_CONVERSATION_RESPONSE_CHARACTERS) {
      throw new GahyeonClientError('responseLimit')
    }
    completedRequests.add(requestId)
    const streamed = messages.value.find(message => message.requestId === requestId)
    if (streamed) {
      streamed.id = response.runId || streamed.id
      streamed.text = response.content
      delete streamed.requestId
    } else {
      appendMessage({ id: response.runId || crypto.randomUUID(), role: 'gahyeon', text: response.content })
    }
    // Durable SSE normally carries the semantic terminal, but HTTP completion
    // is the local fallback while the event stream is disconnected. Only the
    // current request may change presentation; a superseded response is data-only.
    if (activeRequestId.value === requestId) {
      applyPresentationEvent('conversation.completed')
    }
    enqueueSpeech(pending, pending.sentences.accept(
      unseenFinalText(response.content, pending.receivedText),
    ))
    enqueueSpeech(pending, pending.sentences.finish())
    await pending.speech?.finish()
    if (activeRequestId.value === requestId && conversationExpression) {
      applySpeechEvent('avatar.expression', { expression: 'neutral', intensity: 0 })
    }
  }
  catch (error) {
    pending.speech?.cancel()
    completedRequests.add(requestId)
    const partial = messages.value.find(message => message.requestId === requestId)
    if (partial) delete partial.requestId
    if (activeRequestId.value === requestId) {
      try {
        await gahyeon.cancelConversation(sessionId, installationId)
      }
      catch {
        // Local timeout/failure already owns presentation recovery. Core
        // cancellation is best effort when the transport itself is down.
      }
      applyPresentationEvent('conversation.failed')
      appendMessage({
        id: crypto.randomUUID(),
        role: 'system',
        text: localizedError(error),
      })
    }
  }
  finally {
    pendingConversations.delete(requestId)
    boundTimeline()
    if (activeRequestId.value === requestId) {
      activeRequestId.value = undefined
      sending.value = false
    }
    await scrollToEnd()
  }
}

function applyConversationDelta(data: unknown) {
  if (!data || typeof data !== 'object') return
  const payload = data as { requestId?: unknown, delta?: unknown }
  if (typeof payload.requestId !== 'string' || typeof payload.delta !== 'string' || !payload.delta) return
  if (completedRequests.has(payload.requestId)) return
  let entry = messages.value.find(message => message.requestId === payload.requestId)
  if (!entry) {
    entry = { id: `stream:${payload.requestId}`, requestId: payload.requestId, role: 'gahyeon', text: '' }
    appendMessage(entry)
  }
  const pending = pendingConversations.get(payload.requestId)
  try {
    entry.text = appendBoundedResponseText(entry.text, payload.delta)
    if (pending) {
      pending.receivedText = appendBoundedResponseText(pending.receivedText, payload.delta)
    }
  }
  catch {
    failOversizedConversation(payload.requestId, entry, pending)
    return
  }
  if (pending) {
    if (!pending.firstDeltaRecorded) {
      pending.firstDeltaRecorded = true
      latencyMetrics.record('request_to_first_delta', performance.now() - pending.admittedAt)
    }
    enqueueSpeech(pending, pending.sentences.accept(payload.delta))
  }
  void scrollToEnd()
}

function failOversizedConversation(
  requestId: string,
  entry: ChatEntry,
  pending?: { speech?: SpeechSequence },
) {
  completedRequests.add(requestId)
  unsuccessfulRequests.add(requestId)
  pending?.speech?.cancel()
  delete entry.requestId
  if (activeRequestId.value === requestId) {
    activeRequestId.value = undefined
    sending.value = false
    applyPresentationEvent('conversation.failed')
    addSystemMessage(localizedError(new GahyeonClientError('responseLimit')))
  }
  void gahyeon.cancelConversation(sessionId, installationId).catch(() => {})
}

function applyConversationTerminal(type: string, data: unknown) {
  const terminal = parseConversationTerminal(type, data)
  if (!terminal) return
  // The POST response/catch may have already finalized this request before the
  // scheduled durable event reaches SSE. Never materialize that completion twice.
  if (completedRequests.has(terminal.requestId)) return
  completedRequests.add(terminal.requestId)
  const unsuccessful = isUnsuccessfulConversationTerminal(terminal.type)
  if (unsuccessful) {
    unsuccessfulRequests.add(terminal.requestId)
    pendingConversations.get(terminal.requestId)?.speech?.cancel()
    if (activeRequestId.value === terminal.requestId) {
      activeRequestId.value = undefined
      sending.value = false
    }
  }

  const orphan = reconcileConversationTerminal(
    messages.value,
    pendingConversations.has(terminal.requestId) && !unsuccessful,
    terminal,
  )
  if (orphan) appendMessage({
    id: orphan.id ?? crypto.randomUUID(),
    role: 'gahyeon',
    text: orphan.text,
  })
}

function enqueueSpeech(
  pending: { speech?: SpeechSequence },
  sentences: string[],
) {
  for (const sentence of sentences) void pending.speech?.enqueue(sentence)
}

function speechListener(admittedAt: number) {
  return {
    onStart: () => {
      latencyMetrics.record('request_to_first_audio', performance.now() - admittedAt)
      applySpeechEvent('avatar.speech.started', {})
    },
    onLevel: (level: number) => applySpeechEvent('avatar.speech.level', { level }),
    onStop: () => applySpeechEvent('avatar.speech.stopped', {}),
  }
}

async function toggleRecording() {
  if (microphoneEnabled.value) {
    microphoneEnabled.value = false
    localStorage.setItem('gahyeon.microphoneEnabled', 'false')
    cancelTranscription()
    recording.value = false
    await recorder.cancel()
    applyPresentationEvent('perception.voice.cancelled')
    return
  }
  if (!transcriptionReady.value) {
    localStorage.setItem('gahyeon.microphoneEnabled', 'false')
    addSystemMessage(t('voice.sttUnavailable'))
    return
  }
  microphoneEnabled.value = true
  localStorage.setItem('gahyeon.microphoneEnabled', 'true')
  await startContinuousListening()
}

async function startContinuousListening() {
  if (!microphoneEnabled.value || recording.value || transcribing.value) return
  try {
    await recorder.start({
      onVoiceStarted: () => {
        const detectedAt = performance.now()
        stopSpeechForBargeIn()
        applyPresentationEvent('perception.voice.started')
        latencyMetrics.record('vad_to_listening_state', performance.now() - detectedAt)
      },
      onVoiceEnded: () => void finishRecording(),
    })
    recording.value = true
  }
  catch (error) {
    microphoneEnabled.value = false
    localStorage.setItem('gahyeon.microphoneEnabled', 'false')
    recording.value = false
    applyPresentationEvent('perception.voice.cancelled')
    addSystemMessage(localizedError(error))
  }
}

async function finishRecording() {
  if (!recording.value || transcribing.value) return
  recording.value = false
  applyPresentationEvent('perception.voice.ended')
  if (recordingTimeout !== undefined) window.clearTimeout(recordingTimeout)
  recordingTimeout = undefined
  transcribing.value = true
  const generation = ++transcriptionGeneration
  const vadEndedAt = performance.now()
  try {
    const wav = await recorder.stop()
    const transcript = (await gahyeon.transcribeWav(wav)).trim()
    if (generation !== transcriptionGeneration) return
    latencyMetrics.record('vad_end_to_stt_final', performance.now() - vadEndedAt)
    if (!transcript) throw new Error(t('voice.noTranscript'))
    transcribing.value = false
    input.value = transcript
    await send(true)
  }
  catch (error) {
    if (generation !== transcriptionGeneration) return
    applyPresentationEvent('perception.voice.cancelled')
    addSystemMessage(localizedError(error))
  }
  finally {
    if (generation === transcriptionGeneration) transcribing.value = false
    if (microphoneEnabled.value) await startContinuousListening()
  }
}

function cancelTranscription() {
  transcriptionGeneration++
  transcribing.value = false
  gahyeon.cancelSpeechRequests()
  applyPresentationEvent('perception.voice.cancelled')
}

async function cancelActiveConversation() {
  const requestId = activeRequestId.value
  if (!requestId) return
  activeRequestId.value = undefined
  sending.value = false
  completedRequests.add(requestId)
  unsuccessfulRequests.add(requestId)
  pendingConversations.get(requestId)?.speech?.cancel()
  const partial = messages.value.find(message => message.requestId === requestId)
  if (partial) delete partial.requestId
  await gahyeon.cancelConversation(sessionId, installationId)
}

function toggleVoiceOutput() {
  voiceOutput.value = !voiceOutput.value
  localStorage.setItem('gahyeon.voiceOutput', String(voiceOutput.value))
  if (!voiceOutput.value) {
    gahyeon.cancelSpeechRequests()
    speechPlayer.stop()
    applySpeechEvent('avatar.speech.stopped', {})
  }
}

function closeCharacterWindow() {
  window.close()
}

function refreshCharacterWindow() {
  window.location.reload()
}

function selectCharacter(id: DesktopCharacterId) {
  selectedCharacterId.value = id
  localStorage.setItem(DESKTOP_CHARACTER_STORAGE_KEY, id)
}

function restoreSelectedCharacter(event: StorageEvent) {
  if (event.key === DESKTOP_CHARACTER_STORAGE_KEY) {
    selectedCharacterId.value = restoreDesktopCharacter(localStorage)
  }
  if (event.key === 'gahyeon.voiceOutput') {
    const enabled = localStorage.getItem('gahyeon.voiceOutput') !== 'false'
    if (enabled !== voiceOutput.value) toggleVoiceOutput()
  }
  if (event.key === 'gahyeon.microphoneEnabled') {
    const enabled = localStorage.getItem('gahyeon.microphoneEnabled') === 'true'
    if (enabled !== microphoneEnabled.value) void toggleRecording()
  }
}

function syncControlsGlassSurface(event: Event) {
  gahyeon.setControlsGlassExpanded((event.currentTarget as HTMLDetailsElement).open)
}

function stopSpeechForBargeIn() {
  const requestedAt = performance.now()
  gahyeon.cancelSpeechRequests()
  void speechPlayer.stop().then(stopped => {
    if (stopped) latencyMetrics.record('barge_in_audio_stop', performance.now() - requestedAt)
  })
  applySpeechEvent('avatar.speech.stopped', {})
}

function applySpeechEvent(event: string, payload: Record<string, unknown>) {
  stageState.value = reduceStageEvent(stageState.value, { event, data: { payload } })
}

function applyPresentationEvent(event: string, data: Record<string, unknown> = {}) {
  stageState.value = reduceStageEvent(stageState.value, { event, data })
}

function addSystemMessage(text: string) {
  appendMessage({ id: crypto.randomUUID(), role: 'system', text })
  void scrollToEnd()
}

function appendMessage(entry: ChatEntry) {
  messages.value.push(entry)
  boundTimeline()
}

function boundTimeline() {
  trimTimeline(
    messages.value,
    MAXIMUM_TIMELINE_ENTRIES,
    new Set(pendingConversations.keys()),
  )
}

async function scrollToEnd() {
  await nextTick()
  messageList.value?.scrollTo({ top: messageList.value.scrollHeight, behavior: 'smooth' })
}

function persistentId(key: string, prefix: string) {
  const existing = localStorage.getItem(key)
  if (existing) return existing
  const value = `${prefix}-${crypto.randomUUID()}`
  localStorage.setItem(key, value)
  return value
}
</script>

<template>
  <main class="shell" :class="{ 'character-window': characterWindow, 'chat-window': chatWindow }">
    <section class="stage" :aria-label="t('stage.label')">
      <header class="brand">
        <span class="brand-mark">G</span>
        <div>
          <h1>Gahyeon</h1>
          <p>{{ t('app.tagline') }}</p>
        </div>
      </header>

      <div class="ambient ambient-one" />
      <div class="ambient ambient-two" />
      <StageView
        :key="selectedCharacterId"
        v-if="worldStageReady || (characterWindow && characterMediaUrl)"
        :state="stageState"
        :model-url="modelUrl"
        :hero-manifest-url="heroManifestUrl"
        :animation-manifest-url="animationManifestUrl"
        :world-url="worldUrl"
        :looking-glass-enabled="lookingGlassEnabled"
        :character-window="characterWindow"
        :character-media-url="characterMediaUrl"
        :character-id="selectedCharacter.id"
        :character-name="selectedCharacter.displayName"
        @world-action-arrived="completeWorldAction"
        @renderer-presence="setRendererPresence"
      />

      <div v-if="!characterWindow" class="presence">
        <span class="presence-dot" :class="streamState" />
        <div>
          <strong>{{ statusLabel }}</strong>
          <small>Desktop · {{ sessionId.slice(0, 18) }}</small>
        </div>
      </div>
    </section>

    <nav v-if="characterWindow" class="character-controls-island" :class="{ expanded: controlsExpanded }" aria-label="캐릭터 컨트롤">
      <Transition name="island-drawer">
        <div v-if="controlsExpanded" class="character-island-actions">
          <button type="button" aria-label="캐릭터 선택" @click="gahyeon.openControlsPanel()">
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="8" r="3"/><path d="M6.5 19c.7-3.2 2.5-5 5.5-5s4.8 1.8 5.5 5"/></svg>
          </button>
          <button type="button" aria-label="화면 새로고침" @click="refreshCharacterWindow">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19 7v5h-5"/><path d="M18 12a7 7 0 1 0-2 5"/></svg>
          </button>
          <button type="button" aria-label="캐릭터 중앙 정렬">
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="7"/><circle cx="12" cy="12" r="2"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/></svg>
          </button>
          <button type="button" aria-label="배경 전환">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3a9 9 0 1 0 0 18V3Z"/><circle cx="12" cy="12" r="9"/></svg>
          </button>
          <button type="button" aria-label="항상 위에 표시">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m8 3 8 8-3 1 5 5-1 1-5-5-1 3-8-8 5-5Z"/><path d="m8 16-4 4"/></svg>
          </button>
          <button type="button" class="danger" aria-label="캐릭터 종료" @click="closeCharacterWindow">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v8M6.3 6.3a8 8 0 1 0 11.4 0"/></svg>
          </button>
        </div>
      </Transition>
      <div class="character-island-main">
        <button class="character-island-toggle" type="button" :aria-label="controlsExpanded ? '캐릭터 컨트롤 접기' : '캐릭터 컨트롤 펼치기'" :title="statusLabel" @click="controlsExpanded = !controlsExpanded">
          <svg class="character-controls-glyph" :class="{ rotated: controlsExpanded }" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 15 6-6 6 6" /></svg>
          <span class="presence-dot" :class="streamState" />
        </button>
        <button type="button" aria-label="채팅 열기" @click="gahyeon.openChatWindow()">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 5h14v11H9l-4 3V5Z"/><path d="M8 9h8M8 12h5"/></svg>
        </button>
        <button type="button" :class="{ enabled: microphoneEnabled }" :aria-pressed="microphoneEnabled" aria-label="마이크 켜기 또는 끄기" @click="toggleRecording">
          <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3M8.5 21h7"/></svg>
        </button>
        <button type="button" :class="{ enabled: voiceOutput }" :aria-pressed="voiceOutput" aria-label="스피커 켜기 또는 끄기" @click="toggleVoiceOutput">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 10v4h4l5 4V6L9 10H5Z"/><path d="M17 9a4 4 0 0 1 0 6M19 6.5a8 8 0 0 1 0 11"/></svg>
        </button>
      </div>
    </nav>

    <section class="conversation">
      <header class="conversation-header">
        <div>
          <span class="eyebrow">{{ t('conversation.eyebrow').toUpperCase() }}</span>
          <h2>{{ t('conversation.title') }}</h2>
        </div>
        <div class="conversation-actions">
          <button
            class="voice-toggle"
            type="button"
            :class="{ enabled: voiceOutput && synthesisReady }"
            :disabled="!synthesisReady"
            :aria-label="voiceOutput ? t('voice.disable') : t('voice.enable')"
            @click="toggleVoiceOutput"
          >{{ voiceOutput && synthesisReady ? t('voice.on') : t('voice.off') }}</button>
          <select class="locale-select" :value="locale" :aria-label="t('locale.label')" @change="changeLocale">
            <option value="ko">한국어</option><option value="en">English</option><option value="ja">日本語</option>
          </select>
          <input v-model="displayName" class="name-input" :aria-label="t('identity.displayName')" maxlength="40">
        </div>
      </header>

      <details class="identity-link">
        <summary>{{ identityLinked ? t('identity.linked') : nativeDesktop ? t('identity.linkAction') : t('identity.nativeRequired') }}</summary>
        <form v-if="nativeDesktop && !identityLinked" @submit.prevent="linkIdentity">
          <input v-model="identityLinkCode" maxlength="128" autocomplete="one-time-code"
                 :placeholder="t('identity.linkPlaceholder')" :aria-label="t('identity.linkPlaceholder')">
          <button type="submit" :disabled="identityLinking || !identityLinkCode.trim()">
            {{ identityLinking ? '…' : t('identity.linkSubmit') }}
          </button>
        </form>
        <button v-else-if="nativeDesktop" type="button" class="identity-unlink"
                :disabled="identityLinking" @click="unlinkIdentity">
          {{ t('identity.unlink') }}
        </button>
        <p v-if="identityExpiryWarning" class="identity-expiry-warning">{{ identityExpiryWarning }}</p>
      </details>

      <div ref="messageList" class="messages" aria-live="polite">
        <article v-for="message in messages" :key="message.id" class="message" :class="message.role">
          <span>{{ message.role === 'user' ? displayName : message.role === 'gahyeon' ? t('role.gahyeon') : t('role.system') }}</span>
          <p>{{ message.messageKey ? t(message.messageKey) : message.text }}</p>
        </article>
        <article v-if="sending && !receivingStream" class="message gahyeon typing">
          <span>{{ t('role.gahyeon') }}</span>
          <p><i /><i /><i /></p>
        </article>
      </div>

      <form class="composer" @submit.prevent="send()">
        <button
          type="button"
          class="mic-button"
          :class="{ recording }"
          :disabled="!transcriptionReady"
          :aria-label="recording ? t('voice.recordStop') : t('voice.recordStart')"
          @click="toggleRecording"
        >{{ transcribing ? '…' : recording ? '■' : '●' }}</button>
        <textarea
          v-model="input"
          rows="1"
          :placeholder="t('conversation.placeholder')"
          :aria-label="t('conversation.message')"
          @keydown.enter.exact.prevent="send()"
        />
        <button class="send-button" type="submit" :disabled="!input.trim()" :aria-label="t('conversation.send')">↑</button>
      </form>
    </section>
  </main>
</template>
