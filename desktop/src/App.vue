<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { getGahyeonBridge } from './gahyeon-api'
import StageView from './components/StageView.vue'
import { SpeechPlayer } from './audio/speech-player'
import { WavRecorder } from './audio/wav-recorder'
import { initialStageState, reduceStageEvent } from './stage/stage-state'
import { locale, setLocale, t, type Locale } from './i18n'

interface ChatEntry {
  id: string
  role: 'user' | 'gahyeon' | 'system'
  text: string
}

const installationId = persistentId('gahyeon.installationId', 'installation')
const sessionId = persistentId('gahyeon.sessionId', 'desktop')
const displayName = ref(localStorage.getItem('gahyeon.displayName') ?? t('identity.defaultName'))
const input = ref('')
const sending = ref(false)
const recording = ref(false)
const transcribing = ref(false)
const transcriptionReady = ref(false)
const synthesisReady = ref(false)
const voiceOutput = ref(localStorage.getItem('gahyeon.voiceOutput') !== 'false')
const streamState = ref<'connecting' | 'connected' | 'error'>('connecting')
const messages = ref<ChatEntry[]>([
  { id: 'welcome', role: 'gahyeon', text: t('conversation.welcome') },
])
const stageState = ref(initialStageState)
const messageList = ref<HTMLElement>()
let afterSequence = Number(localStorage.getItem(`gahyeon.cursor.${sessionId}`) ?? '0')
let unsubscribe: (() => void) | undefined
const gahyeon = getGahyeonBridge()
const modelUrl = import.meta.env.VITE_GAHYEON_VRM_URL as string | undefined
const animationManifestUrl = import.meta.env.VITE_GAHYEON_VRMA_MANIFEST as string | undefined
const worldUrl = import.meta.env.VITE_GAHYEON_WORLD_URL as string | undefined
const lookingGlassEnabled = import.meta.env.VITE_GAHYEON_LOOKING_GLASS === 'true'
const worldId = 'gahyeon-home'
const recorder = new WavRecorder()
const speechPlayer = new SpeechPlayer()
let recordingTimeout: number | undefined

const statusLabel = computed(() => ({
  connecting: t('status.connecting'), connected: t('status.connected'), error: t('status.error'),
})[streamState.value])

function changeLocale(event: Event) {
  setLocale((event.target as HTMLSelectElement).value as Locale)
}

onMounted(async () => {
  try {
    const speech = await gahyeon.getSpeechStatus()
    transcriptionReady.value = speech.transcriptionReady
    synthesisReady.value = speech.synthesisReady
  }
  catch {
    transcriptionReady.value = false
    synthesisReady.value = false
  }
  try {
    const snapshot = await gahyeon.getWorldState(worldId)
    stageState.value = reduceStageEvent(stageState.value, {
      event: 'world.state.restored',
      data: snapshot,
    })
  }
  catch {
    streamState.value = 'error'
  }
  unsubscribe = gahyeon.subscribeEvents({ sessionId, afterSequence }, event => {
    stageState.value = reduceStageEvent(stageState.value, event)
    if (event.id) {
      afterSequence = Number(event.id)
      localStorage.setItem(`gahyeon.cursor.${sessionId}`, String(afterSequence))
    }
    if (event.event === 'stream.connected') streamState.value = 'connected'
    if (event.event === 'stream.error') streamState.value = 'error'
  })
})

onBeforeUnmount(() => {
  unsubscribe?.()
  if (recordingTimeout !== undefined) window.clearTimeout(recordingTimeout)
  void recorder.cancel()
  void speechPlayer.dispose()
})

async function send() {
  const text = input.value.trim()
  if (!text || sending.value) return
  localStorage.setItem('gahyeon.displayName', displayName.value)
  input.value = ''
  sending.value = true
  speechPlayer.stop()
  messages.value.push({ id: crypto.randomUUID(), role: 'user', text })
  await scrollToEnd()
  try {
    const response = await gahyeon.sendMessage({
      sessionId,
      requestId: crypto.randomUUID(),
      installationId,
      displayName: displayName.value,
      message: text,
    })
    messages.value.push({ id: response.runId || crypto.randomUUID(), role: 'gahyeon', text: response.content })
    if (voiceOutput.value && synthesisReady.value) {
      await speechPlayer.speak(response.content, gahyeon, {
        onStart: () => applySpeechEvent('avatar.speech.started', {}),
        onLevel: level => applySpeechEvent('avatar.speech.level', { level }),
        onStop: () => applySpeechEvent('avatar.speech.stopped', {}),
      })
    }
  }
  catch (error) {
    messages.value.push({
      id: crypto.randomUUID(),
      role: 'system',
      text: error instanceof Error ? error.message : String(error),
    })
  }
  finally {
    sending.value = false
    await scrollToEnd()
  }
}

async function toggleRecording() {
  if (transcribing.value || sending.value) return
  if (!recording.value) {
    if (!transcriptionReady.value) {
      addSystemMessage(t('voice.sttUnavailable'))
      return
    }
    try {
      speechPlayer.stop()
      await recorder.start()
      recording.value = true
      recordingTimeout = window.setTimeout(() => void toggleRecording(), 20_000)
    }
    catch (error) {
      addSystemMessage(error instanceof Error ? error.message : String(error))
    }
    return
  }

  recording.value = false
  if (recordingTimeout !== undefined) window.clearTimeout(recordingTimeout)
  recordingTimeout = undefined
  transcribing.value = true
  try {
    const wav = await recorder.stop()
    const transcript = (await gahyeon.transcribeWav(wav)).trim()
    if (!transcript) throw new Error(t('voice.noTranscript'))
    input.value = transcript
    await send()
  }
  catch (error) {
    addSystemMessage(error instanceof Error ? error.message : String(error))
  }
  finally {
    transcribing.value = false
  }
}

function toggleVoiceOutput() {
  voiceOutput.value = !voiceOutput.value
  localStorage.setItem('gahyeon.voiceOutput', String(voiceOutput.value))
  if (!voiceOutput.value) {
    speechPlayer.stop()
    applySpeechEvent('avatar.speech.stopped', {})
  }
}

function applySpeechEvent(event: string, payload: Record<string, unknown>) {
  stageState.value = reduceStageEvent(stageState.value, { event, data: { payload } })
}

function addSystemMessage(text: string) {
  messages.value.push({ id: crypto.randomUUID(), role: 'system', text })
  void scrollToEnd()
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
    <section class="stage" aria-label="Gahyeon character stage">
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
        :animation-manifest-url="animationManifestUrl"
        :world-url="worldUrl"
        :looking-glass-enabled="lookingGlassEnabled"
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
          >{{ voiceOutput && synthesisReady ? 'VOICE ON' : 'VOICE OFF' }}</button>
          <select class="locale-select" :value="locale" :aria-label="t('locale.label')" @change="changeLocale">
            <option value="ko">한국어</option><option value="en">English</option>
          </select>
          <input v-model="displayName" class="name-input" :aria-label="t('identity.displayName')" maxlength="40">
        </div>
      </header>

      <div ref="messageList" class="messages" aria-live="polite">
        <article v-for="message in messages" :key="message.id" class="message" :class="message.role">
          <span>{{ message.role === 'user' ? displayName : message.role === 'gahyeon' ? t('role.gahyeon') : t('role.system') }}</span>
          <p>{{ message.text }}</p>
        </article>
        <article v-if="sending" class="message gahyeon typing">
          <span>{{ t('role.gahyeon') }}</span>
          <p><i /><i /><i /></p>
        </article>
      </div>

      <form class="composer" @submit.prevent="send">
        <button
          type="button"
          class="mic-button"
          :class="{ recording }"
          :disabled="!transcriptionReady || sending || transcribing"
          :aria-label="recording ? t('voice.recordStop') : t('voice.recordStart')"
          @click="toggleRecording"
        >{{ transcribing ? '…' : recording ? '■' : '●' }}</button>
        <textarea
          v-model="input"
          rows="1"
          :placeholder="t('conversation.placeholder')"
          :aria-label="t('conversation.message')"
          @keydown.enter.exact.prevent="send"
        />
        <button class="send-button" type="submit" :disabled="sending || !input.trim()" :aria-label="t('conversation.send')">↑</button>
      </form>
    </section>
  </main>
</template>
