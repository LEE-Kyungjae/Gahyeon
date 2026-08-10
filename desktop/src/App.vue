<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { getGahyeonBridge } from './gahyeon-api'

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
const streamState = ref<'connecting' | 'connected' | 'error'>('connecting')
const messages = ref<ChatEntry[]>([
  { id: 'welcome', role: 'gahyeon', text: '여기 있어. 무슨 이야기를 해볼까?' },
])
const messageList = ref<HTMLElement>()
let afterSequence = Number(localStorage.getItem(`gahyeon.cursor.${sessionId}`) ?? '0')
let unsubscribe: (() => void) | undefined
const gahyeon = getGahyeonBridge()

const statusLabel = computed(() => ({
  connecting: 'Core 연결 중',
  connected: 'Core 연결됨',
  error: 'Core 연결 끊김',
})[streamState.value])

onMounted(() => {
  unsubscribe = gahyeon.subscribeEvents({ sessionId, afterSequence }, event => {
    if (event.id) {
      afterSequence = Number(event.id)
      localStorage.setItem(`gahyeon.cursor.${sessionId}`, String(afterSequence))
    }
    if (event.event === 'stream.connected') streamState.value = 'connected'
    if (event.event === 'stream.error') streamState.value = 'error'
  })
})

onBeforeUnmount(() => unsubscribe?.())

async function send() {
  const text = input.value.trim()
  if (!text || sending.value) return
  localStorage.setItem('gahyeon.displayName', displayName.value)
  input.value = ''
  sending.value = true
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
      <div class="avatar-placeholder">
        <div class="avatar-orbit" />
        <span>Avatar renderer</span>
        <small>VRM stage 연결 예정</small>
      </div>

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
        <input v-model="displayName" class="name-input" aria-label="표시 이름" maxlength="40">
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
        <textarea
          v-model="input"
          rows="1"
          placeholder="가현에게 말하기…"
          aria-label="메시지"
          @keydown.enter.exact.prevent="send"
        />
        <button type="submit" :disabled="sending || !input.trim()" aria-label="보내기">↑</button>
      </form>
    </section>
  </main>
</template>
