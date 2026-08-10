import { ref } from 'vue'

export type Locale = 'ko' | 'en'

const messages = {
  ko: {
    'app.tagline': '언제나 가까이에 있는 살아 있는 존재.', 'conversation.eyebrow': '대화',
    'conversation.title': '가현과의 공간', 'conversation.placeholder': '가현에게 말하기…',
    'conversation.message': '메시지', 'conversation.send': '보내기',
    'conversation.welcome': '여기 있어. 무슨 이야기를 해볼까?', 'identity.displayName': '표시 이름',
    'identity.defaultName': '사용자', 'role.gahyeon': '가현', 'role.system': '시스템',
    'status.connecting': 'Core 연결 중', 'status.connected': 'Core 연결됨', 'status.error': 'Core 연결 끊김',
    'voice.disable': '음성 출력 끄기', 'voice.enable': '음성 출력 켜기',
    'voice.recordStart': '마이크 입력', 'voice.recordStop': '녹음 종료',
    'voice.sttUnavailable': 'STT가 준비되지 않았습니다.', 'voice.noTranscript': '음성을 인식하지 못했습니다.',
    'stage.partialAnimation': '일부 VRMA 대신 기본 동작을 사용합니다: {details}',
    'stage.lookingGlassFailure': 'Looking Glass 초기화 실패: {details}',
    'stage.enableLookingGlass': 'LOOKING GLASS 켜기', 'stage.loading': '불러오는 중…', 'locale.label': '언어',
  },
  en: {
    'app.tagline': 'A living presence, close by.', 'conversation.eyebrow': 'Conversation',
    'conversation.title': 'A space with Gahyeon', 'conversation.placeholder': 'Talk to Gahyeon…',
    'conversation.message': 'Message', 'conversation.send': 'Send',
    'conversation.welcome': "I'm here. What would you like to talk about?", 'identity.displayName': 'Display name',
    'identity.defaultName': 'User', 'role.gahyeon': 'Gahyeon', 'role.system': 'System',
    'status.connecting': 'Connecting to Core', 'status.connected': 'Core connected', 'status.error': 'Core disconnected',
    'voice.disable': 'Disable voice output', 'voice.enable': 'Enable voice output',
    'voice.recordStart': 'Start microphone input', 'voice.recordStop': 'Stop recording',
    'voice.sttUnavailable': 'Speech recognition is not ready.', 'voice.noTranscript': 'No speech could be recognized.',
    'stage.partialAnimation': 'Using fallback motion for some VRMA clips: {details}',
    'stage.lookingGlassFailure': 'Looking Glass initialization failed: {details}',
    'stage.enableLookingGlass': 'ENABLE LOOKING GLASS', 'stage.loading': 'LOADING…', 'locale.label': 'Language',
  },
} as const

export type MessageKey = keyof typeof messages.ko

function detectLocale(): Locale {
  const stored = localStorage.getItem('gahyeon.locale')
  if (stored === 'ko' || stored === 'en') return stored
  return navigator.language.toLowerCase().startsWith('ko') ? 'ko' : 'en'
}

export const locale = ref<Locale>(detectLocale())

export function setLocale(next: Locale) {
  locale.value = next
  localStorage.setItem('gahyeon.locale', next)
  document.documentElement.lang = next
}

export function t(key: MessageKey, params: Record<string, string> = {}): string {
  let result: string = messages[locale.value][key]
  for (const [name, value] of Object.entries(params)) result = result.replaceAll(`{${name}}`, value)
  return result
}

document.documentElement.lang = locale.value
