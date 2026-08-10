<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { getGahyeonBridge } from './gahyeon-api'
import StageView from './components/StageView.vue'
import { SpeechPlayer } from './audio/speech-player'
import { WavRecorder } from './audio/wav-recorder'
import { initialStageState, reduceStageEvent } from './stage/stage-state'

interface ChatEntry {
  id: string
  role: 'user' | 'gahyeon' | 'system'
  text: string
}

const installationId = persistentId('gahyeon.installationId', 'installation')
const sessionId = persistentId('gahyeon.sessionId', 'desktop')
const displayName = ref(localStorage.getItem('gahyeon.displayName') ?? '사용자')
const input = ref('')
const sending = ref(false)
const recording = ref(false)
const transcribing = ref(false)
const transcriptionReady = ref(false)
const synthesisReady = ref(false)
const voiceOutput = ref(localStorage.getItem('gahyeon.voiceOutput') !== 'false')
const streamState = ref<'connecting' | 'connected' | 'error'>('connecting')
const messages = ref<ChatEntry[]>([
  { id: 'welcome', role: 'gahyeon', text: '여기 있어. 무슨 이야기를 해볼까?' },
])
const stageState = ref(initialStageState)
const messageList = ref<HTMLElement>()
let afterSequence = Number(localStorage.getItem(`gahyeon.cursor.${sessionId}`) ?? '0')
let unsubscribe: (() => void) | undefined
const gahyeon = getGahyeonBridge()
const modelUrl = import.meta.env.VITE_GAHYEON_VRM_URL as string | undefined
const animationManifestUrl = import.meta.env.VITE_GAHYEON_VRMA_MANIFEST as string | undefined
const lookingGlassEnabled = import.meta.env.VITE_GAHYEON_LOOKING_GLASS === 'true'
const worldId = 'gahyeon-home'
const recorder = new WavRecorder()
const speechPlayer = new SpeechPlayer()
let recordingTimeout: number | undefined

const statusLabel = computed(() => ({
  connecting: 'Core 연결 중',
  connected: 'Core 연결됨',
  error: 'Core 연결 끊김',
})[streamState.value])

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
      addSystemMessage('STT가 준비되지 않았습니다.')
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
    if (!transcript) throw new Error('음성을 인식하지 못했습니다.')
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
          <p>A living presence, close by.</p>
        </div>
      </header>

      <div class="ambient ambient-one" />
      <div class="ambient ambient-two" />
      <StageView
        :state="stageState"
        :model-url="modelUrl"
        :animation-manifest-url="animationManifestUrl"
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
          <span class="eyebrow">CONVERSATION</span>
          <h2>가현과의 공간</h2>
        </div>
        <div class="conversation-actions">
          <button
            class="voice-toggle"
            type="button"
            :class="{ enabled: voiceOutput && synthesisReady }"
            :disabled="!synthesisReady"
            :aria-label="voiceOutput ? '음성 출력 끄기' : '음성 출력 켜기'"
            @click="toggleVoiceOutput"
          >{{ voiceOutput && synthesisReady ? 'VOICE ON' : 'VOICE OFF' }}</button>
          <input v-model="displayName" class="name-input" aria-label="표시 이름" maxlength="40">
        </div>
      </header>

      <div ref="messageList" class="messages" aria-live="polite">
        <article v-for="message in messages" :key="message.id" class="message" :class="message.role">
          <span>{{ message.role === 'user' ? displayName : message.role === 'gahyeon' ? '가현' : '시스템' }}</span>
          <p>{{ message.text }}</p>
        </article>
        <article v-if="sending" class="message gahyeon typing">
          <span>가현</span>
          <p><i /><i /><i /></p>
        </article>
      </div>

      <form class="composer" @submit.prevent="send">
        <button
          type="button"
          class="mic-button"
          :class="{ recording }"
          :disabled="!transcriptionReady || sending || transcribing"
          :aria-label="recording ? '녹음 종료' : '마이크 입력'"
          @click="toggleRecording"
        >{{ transcribing ? '…' : recording ? '■' : '●' }}</button>
        <textarea
          v-model="input"
          rows="1"
          placeholder="가현에게 말하기…"
          aria-label="메시지"
          @keydown.enter.exact.prevent="send"
        />
        <button class="send-button" type="submit" :disabled="sending || !input.trim()" aria-label="보내기">↑</button>
      </form>
    </section>
  </main>
</template>
