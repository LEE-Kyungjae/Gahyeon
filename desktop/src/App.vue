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
import { locale, setLocale, t, type Locale, type MessageKey } from './i18n'
import { GahyeonClientError, localizedError } from './client-error'
import { LatencyMetrics } from './runtime/latency-metrics'
import { DurableEventCursor } from './runtime/durable-event-cursor'

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
const transcribing = ref(false)
const transcriptionReady = ref(false)
const synthesisReady = ref(false)
const voiceOutput = ref(localStorage.getItem('gahyeon.voiceOutput') !== 'false')
const identityLinkCode = ref('')
const identityLinking = ref(false)
const identityLinked = ref(false)
const identityCredentialExpiresAt = ref<string>()
const nativeDesktop = window.gahyeon !== undefined
const streamState = ref<'connecting' | 'connected' | 'error'>('connecting')
const completedRequests = new RecentRequestRegistry()
const unsuccessfulRequests = new RecentRequestRegistry()
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
const stageState = ref(initialStageState)
const messageList = ref<HTMLElement>()
const durableEvents = new DurableEventCursor(
  localStorage.getItem(`gahyeon.cursor.${sessionId}`),
)
let unsubscribe: (() => void) | undefined
const gahyeon = getGahyeonBridge()
const modelUrl = import.meta.env.VITE_GAHYEON_VRM_URL as string | undefined
const heroManifestUrl = import.meta.env.VITE_GAHYEON_HERO_MANIFEST_URL as string | undefined
const animationManifestUrl = import.meta.env.VITE_GAHYEON_VRMA_MANIFEST as string | undefined
const worldUrl = import.meta.env.VITE_GAHYEON_WORLD_URL as string | undefined
const lookingGlassEnabled = import.meta.env.VITE_GAHYEON_LOOKING_GLASS === 'true'
const worldId = 'gahyeon-home'
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
  }
  catch {
    transcriptionReady.value = false
    synthesisReady.value = false
  }
}

async function loadWorldSnapshot() {
  try {
    const snapshot = await gahyeon.getWorldState(worldId)
    stageState.value = reduceStageEvent(stageState.value, {
      event: 'world.state.restored',
      data: snapshot,
    })
  }
  catch {
    // The local stage remains alive with its default world. Event-stream
    // health is owned only by stream.connected/stream.error callbacks.
  }
}

async function completeWorldAction(action: PendingWorldAction) {
  try {
    await gahyeon.completeWorldAction(worldId, {
      installationId,
      actionId: action.actionId,
      expectedRevision: action.expectedRevision,
      finalPosition: action.position,
    })
  }
  catch {
    // Core owns a bounded headless completion fallback. Losing this optional
    // renderer acknowledgement must not stop local idle/reflex animation.
  }
}

function subscribeToCoreEvents() {
  unsubscribe = gahyeon.subscribeEvents({
    sessionId,
    installationId,
    afterSequence: durableEvents.current(),
  }, event => {
    const cursorAdmission = durableEvents.prepare(event)
    if (!cursorAdmission) return
    stageState.value = reduceStageEvent(stageState.value, event)
    if (event.event === 'stream.connected') streamState.value = 'connected'
    if (event.event === 'stream.error') streamState.value = 'error'
    if (event.event === 'conversation.delta') applyConversationDelta(event.data)
    applyConversationTerminal(event.event, event.data)
    if (durableEvents.commit(cursorAdmission)) {
      localStorage.setItem(
        `gahyeon.cursor.${sessionId}`,
        String(durableEvents.current()),
      )
    }
  })
}

onBeforeUnmount(() => {
  unsubscribe?.()
  gahyeon.cancelSpeechRequests()
  if (recordingTimeout !== undefined) window.clearTimeout(recordingTimeout)
  void recorder.cancel()
  void speechPlayer.dispose()
})

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
  const pending = {
    sentences: new IncrementalSentenceAccumulator(),
    speech: voiceOutput.value && synthesisReady.value
      ? speechPlayer.beginSequence(gahyeon, speechListener(admittedAt))
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
  if (transcribing.value) cancelTranscription()
  if (!recording.value) {
    if (!transcriptionReady.value) {
      addSystemMessage(t('voice.sttUnavailable'))
      return
    }
    try {
      stopSpeechForBargeIn()
      applyPresentationEvent('perception.voice.started')
      try {
        await cancelActiveConversation()
      } catch {
        // Local audio and presentation are already cancelled; a submitted
        // transcript will also supersede the old server generation.
      }
      await recorder.start({
        onVoiceStarted: () => {
          const detectedAt = performance.now()
          applyPresentationEvent('perception.voice.started')
          latencyMetrics.record('vad_to_listening_state', performance.now() - detectedAt)
        },
        onVoiceEnded: () => void finishRecording(),
      })
      recording.value = true
      recordingTimeout = window.setTimeout(() => void finishRecording(), 20_000)
    }
    catch (error) {
      applyPresentationEvent('perception.voice.cancelled')
      addSystemMessage(localizedError(error))
    }
    return
  }

  await finishRecording()
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
  <main class="shell">
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
        :state="stageState"
        :model-url="modelUrl"
        :hero-manifest-url="heroManifestUrl"
        :animation-manifest-url="animationManifestUrl"
        :world-url="worldUrl"
        :looking-glass-enabled="lookingGlassEnabled"
        @world-action-arrived="completeWorldAction"
      />

      <div class="presence">
        <span class="presence-dot" :class="streamState" />
        <div>
          <strong>{{ statusLabel }}</strong>
          <small>Desktop · {{ sessionId.slice(0, 18) }}</small>
        </div>
      </div>
    </section>

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
