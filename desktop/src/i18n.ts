import { ref } from 'vue'

export type Locale = 'ko' | 'en' | 'ja'

const messages = {
  ko: {
    'app.tagline': '언제나 가까이에 있는 살아 있는 존재.', 'conversation.eyebrow': '대화',
    'conversation.title': '가현과의 공간', 'conversation.placeholder': '가현에게 말하기…',
    'conversation.message': '메시지', 'conversation.send': '보내기',
    'conversation.welcome': '여기 있어. 무슨 이야기를 해볼까?', 'identity.displayName': '표시 이름',
    'identity.linkAction': 'Discord 계정 연결', 'identity.linked': 'Discord 연결됨',
    'identity.linkPlaceholder': 'Discord에서 받은 일회성 코드', 'identity.linkSubmit': '연결',
    'identity.linkSuccess': 'Discord 계정과 이 Desktop 설치를 연결했습니다.',
    'identity.unlink': '이 기기 연결 해제', 'identity.unlinkSuccess': '이 Desktop 기기 연결을 해제했습니다.',
    'identity.expiryWarning': '계정 credential이 {date}에 만료됩니다. Discord에서 새 연결 코드를 발급해 갱신하세요.',
    'identity.nativeRequired': '계정 연결은 Gahyeon Desktop 앱에서 사용할 수 있습니다.',
    'identity.defaultName': '사용자', 'role.gahyeon': '가현', 'role.system': '시스템',
    'status.connecting': 'Core 연결 중', 'status.connected': 'Core 연결됨', 'status.error': 'Core 연결 끊김',
    'voice.disable': '음성 출력 끄기', 'voice.enable': '음성 출력 켜기',
    'voice.on': '음성 켜짐', 'voice.off': '음성 꺼짐',
    'voice.recordStart': '마이크 입력', 'voice.recordStop': '녹음 종료',
    'voice.microphoneOn': '마이크 켜기', 'voice.microphoneOff': '마이크 끄기',
    'voice.sttUnavailable': 'STT가 준비되지 않았습니다.', 'voice.noTranscript': '음성을 인식하지 못했습니다.',
    'stage.label': 'Gahyeon 캐릭터 Stage', 'stage.partialAnimation': '일부 VRMA 대신 기본 동작을 사용합니다: {details}',
    'stage.lookingGlassFailure': 'Looking Glass 초기화 실패: {details}',
    'stage.worldFailure': '3D World 에셋을 불러오지 못해 기본 World를 사용합니다: {details}',
    'stage.enableLookingGlass': 'LOOKING GLASS 켜기', 'stage.loading': '불러오는 중…', 'locale.label': '언어',
    'error.conversation': '대화 요청에 실패했습니다. ({details})', 'error.world': 'World State를 불러오지 못했습니다. ({details})',
    'error.responseLimit': '응답이 너무 길어 안전하게 중단했습니다.',
    'error.speechStatus': '음성 상태를 확인하지 못했습니다. ({details})', 'error.transcription': '음성 인식에 실패했습니다. ({details})',
    'error.speechSegments': '음성 문장 분할에 실패했습니다. ({details})', 'error.synthesis': '음성 합성에 실패했습니다. ({details})',
    'error.eventStream': 'Gahyeon Core event stream에 연결할 수 없습니다.', 'error.recorderInactive': '현재 녹음 중이 아닙니다.',
    'error.recordingShort': '녹음이 너무 짧습니다.', 'error.vrmInvalid': '올바른 VRM 모델이 아닙니다.',
    'error.vrmaManifest': 'VRMA manifest를 불러오지 못했습니다. ({details})', 'error.vrmaClip': 'VRMA clip이 없습니다.',
    'error.heroManifest': '승인된 Gahyeon Hero 자산을 검증하지 못했습니다. ({details})',
    'error.identityLink': '계정 연결에 실패했습니다. 코드를 확인하거나 새 코드를 발급해 주세요. ({details})',
    'error.unknown': '알 수 없는 오류가 발생했습니다. ({details})',
  },
  en: {
    'app.tagline': 'A living presence, close by.', 'conversation.eyebrow': 'Conversation',
    'conversation.title': 'A space with Gahyeon', 'conversation.placeholder': 'Talk to Gahyeon…',
    'conversation.message': 'Message', 'conversation.send': 'Send',
    'conversation.welcome': "I'm here. What would you like to talk about?", 'identity.displayName': 'Display name',
    'identity.linkAction': 'Link Discord account', 'identity.linked': 'Discord linked',
    'identity.linkPlaceholder': 'One-time code from Discord', 'identity.linkSubmit': 'Link',
    'identity.linkSuccess': 'This Desktop installation is now linked to your Discord account.',
    'identity.unlink': 'Unlink this device', 'identity.unlinkSuccess': 'This Desktop device has been unlinked.',
    'identity.expiryWarning': 'Your account credential expires on {date}. Issue a new Discord link code to rotate it.',
    'identity.nativeRequired': 'Account linking is available in the Gahyeon Desktop app.',
    'identity.defaultName': 'User', 'role.gahyeon': 'Gahyeon', 'role.system': 'System',
    'status.connecting': 'Connecting to Core', 'status.connected': 'Core connected', 'status.error': 'Core disconnected',
    'voice.disable': 'Disable voice output', 'voice.enable': 'Enable voice output',
    'voice.on': 'VOICE ON', 'voice.off': 'VOICE OFF',
    'voice.recordStart': 'Start microphone input', 'voice.recordStop': 'Stop recording',
    'voice.microphoneOn': 'Turn microphone on', 'voice.microphoneOff': 'Turn microphone off',
    'voice.sttUnavailable': 'Speech recognition is not ready.', 'voice.noTranscript': 'No speech could be recognized.',
    'stage.label': 'Gahyeon character stage', 'stage.partialAnimation': 'Using fallback motion for some VRMA clips: {details}',
    'stage.lookingGlassFailure': 'Looking Glass initialization failed: {details}',
    'stage.worldFailure': 'Could not load the 3D world asset; using the fallback world: {details}',
    'stage.enableLookingGlass': 'ENABLE LOOKING GLASS', 'stage.loading': 'LOADING…', 'locale.label': 'Language',
    'error.conversation': 'The conversation request failed. ({details})', 'error.world': 'Could not load World State. ({details})',
    'error.responseLimit': 'The response was too long and was stopped safely.',
    'error.speechStatus': 'Could not read speech status. ({details})', 'error.transcription': 'Speech recognition failed. ({details})',
    'error.speechSegments': 'Speech segmentation failed. ({details})', 'error.synthesis': 'Speech synthesis failed. ({details})',
    'error.eventStream': 'Could not connect to the Gahyeon Core event stream.', 'error.recorderInactive': 'No recording is active.',
    'error.recordingShort': 'The recording is too short.', 'error.vrmInvalid': 'The file is not a valid VRM model.',
    'error.vrmaManifest': 'Could not load the VRMA manifest. ({details})', 'error.vrmaClip': 'The VRMA clip is missing.',
    'error.heroManifest': 'Could not verify the approved Gahyeon Hero asset. ({details})',
    'error.identityLink': 'Account linking failed. Check the code or issue a new one. ({details})',
    'error.unknown': 'An unknown error occurred. ({details})',
  },
  ja: {
    'app.tagline': 'いつもそばにいる、生きた存在。', 'conversation.eyebrow': '会話',
    'conversation.title': 'ガヒョンと過ごす空間', 'conversation.placeholder': 'ガヒョンに話しかける…',
    'conversation.message': 'メッセージ', 'conversation.send': '送信',
    'conversation.welcome': 'ここにいるよ。何を話そうか？', 'identity.displayName': '表示名',
    'identity.linkAction': 'Discordアカウントを連携', 'identity.linked': 'Discord連携済み',
    'identity.linkPlaceholder': 'Discordで発行したワンタイムコード', 'identity.linkSubmit': '連携',
    'identity.linkSuccess': 'このDesktopをDiscordアカウントに連携しました。',
    'identity.unlink': 'このデバイスの連携を解除', 'identity.unlinkSuccess': 'このDesktopの連携を解除しました。',
    'identity.expiryWarning': 'アカウントcredentialは{date}に期限切れになります。Discordで新しい連携コードを発行してください。',
    'identity.nativeRequired': 'アカウント連携はGahyeon Desktopアプリで利用できます。',
    'identity.defaultName': 'ユーザー', 'role.gahyeon': 'ガヒョン', 'role.system': 'システム',
    'status.connecting': 'Coreに接続中', 'status.connected': 'Coreに接続済み', 'status.error': 'Coreとの接続が切れました',
    'voice.disable': '音声出力をオフにする', 'voice.enable': '音声出力をオンにする',
    'voice.on': '音声オン', 'voice.off': '音声オフ',
    'voice.recordStart': 'マイク入力を開始', 'voice.recordStop': '録音を終了',
    'voice.microphoneOn': 'マイクをオン', 'voice.microphoneOff': 'マイクをオフ',
    'voice.sttUnavailable': '音声認識の準備ができていません。', 'voice.noTranscript': '音声を認識できませんでした。',
    'stage.label': 'Gahyeonキャラクターステージ', 'stage.partialAnimation': '一部のVRMAの代わりに基本モーションを使用します: {details}',
    'stage.lookingGlassFailure': 'Looking Glassの初期化に失敗しました: {details}',
    'stage.worldFailure': '3D Worldアセットを読み込めないため、基本Worldを使用します: {details}',
    'stage.enableLookingGlass': 'LOOKING GLASSを有効化', 'stage.loading': '読み込み中…', 'locale.label': '言語',
    'error.conversation': '会話リクエストに失敗しました。({details})', 'error.world': 'World Stateを読み込めませんでした。({details})',
    'error.responseLimit': '応答が長すぎるため、安全に停止しました。',
    'error.speechStatus': '音声状態を確認できませんでした。({details})', 'error.transcription': '音声認識に失敗しました。({details})',
    'error.speechSegments': '音声の文分割に失敗しました。({details})', 'error.synthesis': '音声合成に失敗しました。({details})',
    'error.eventStream': 'Gahyeon Coreのイベントストリームに接続できません。', 'error.recorderInactive': '現在録音していません。',
    'error.recordingShort': '録音が短すぎます。', 'error.vrmInvalid': '有効なVRMモデルではありません。',
    'error.vrmaManifest': 'VRMA manifestを読み込めませんでした。({details})', 'error.vrmaClip': 'VRMA clipがありません。',
    'error.heroManifest': '承認済みのGahyeon Heroアセットを検証できませんでした。({details})',
    'error.identityLink': 'アカウント連携に失敗しました。コードを確認するか再発行してください。({details})',
    'error.unknown': '不明なエラーが発生しました。({details})',
  },
} as const

export type MessageKey = keyof typeof messages.ko

function detectLocale(): Locale {
  const storage = typeof window === 'undefined' ? undefined : window.localStorage
  const stored = storage?.getItem('gahyeon.locale')
  if (stored === 'ko' || stored === 'en' || stored === 'ja') return stored
  const language = globalThis.navigator?.language ?? 'en'
  if (language.toLowerCase().startsWith('ko')) return 'ko'
  if (language.toLowerCase().startsWith('ja')) return 'ja'
  return 'en'
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
