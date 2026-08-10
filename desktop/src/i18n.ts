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
    'stage.worldFailure': '3D World 에셋을 불러오지 못해 기본 World를 사용합니다: {details}',
    'stage.enableLookingGlass': 'LOOKING GLASS 켜기', 'stage.loading': '불러오는 중…', 'locale.label': '언어',
    'error.conversation': '대화 요청에 실패했습니다. ({details})', 'error.world': 'World State를 불러오지 못했습니다. ({details})',
    'error.speechStatus': '음성 상태를 확인하지 못했습니다. ({details})', 'error.transcription': '음성 인식에 실패했습니다. ({details})',
    'error.speechSegments': '음성 문장 분할에 실패했습니다. ({details})', 'error.synthesis': '음성 합성에 실패했습니다. ({details})',
    'error.eventStream': 'Gahyeon Core event stream에 연결할 수 없습니다.', 'error.recorderInactive': '현재 녹음 중이 아닙니다.',
    'error.recordingShort': '녹음이 너무 짧습니다.', 'error.vrmInvalid': '올바른 VRM 모델이 아닙니다.',
    'error.vrmaManifest': 'VRMA manifest를 불러오지 못했습니다. ({details})', 'error.vrmaClip': 'VRMA clip이 없습니다.',
    'error.unknown': '알 수 없는 오류가 발생했습니다. ({details})',
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
    'stage.worldFailure': 'Could not load the 3D world asset; using the fallback world: {details}',
    'stage.enableLookingGlass': 'ENABLE LOOKING GLASS', 'stage.loading': 'LOADING…', 'locale.label': 'Language',
    'error.conversation': 'The conversation request failed. ({details})', 'error.world': 'Could not load World State. ({details})',
    'error.speechStatus': 'Could not read speech status. ({details})', 'error.transcription': 'Speech recognition failed. ({details})',
    'error.speechSegments': 'Speech segmentation failed. ({details})', 'error.synthesis': 'Speech synthesis failed. ({details})',
    'error.eventStream': 'Could not connect to the Gahyeon Core event stream.', 'error.recorderInactive': 'No recording is active.',
    'error.recordingShort': 'The recording is too short.', 'error.vrmInvalid': 'The file is not a valid VRM model.',
    'error.vrmaManifest': 'Could not load the VRMA manifest. ({details})', 'error.vrmaClip': 'The VRMA clip is missing.',
    'error.unknown': 'An unknown error occurred. ({details})',
  },
} as const

export type MessageKey = keyof typeof messages.ko

function detectLocale(): Locale {
  const storage = typeof window === 'undefined' ? undefined : window.localStorage
  const stored = storage?.getItem('gahyeon.locale')
  if (stored === 'ko' || stored === 'en') return stored
  const language = globalThis.navigator?.language ?? 'en'
  return language.toLowerCase().startsWith('ko') ? 'ko' : 'en'
}

export const locale = ref<Locale>(detectLocale())

export function setLocale(next: Locale) {
  locale.value = next
  if (typeof window !== 'undefined') window.localStorage.setItem('gahyeon.locale', next)
  if (globalThis.document) document.documentElement.lang = next
}

export function t(key: MessageKey, params: Record<string, string> = {}): string {
  let result: string = messages[locale.value][key]
  for (const [name, value] of Object.entries(params)) result = result.replaceAll(`{${name}}`, value)
  return result
}

if (globalThis.document) document.documentElement.lang = locale.value
