# Character 데이터 모델과 행동 경계

## 표현 상태

기존 Core의 단일 `EmotionState(name, intensity)`와 `avatar.expression`은 호환을 위해
유지한다. Unreal v1은 이를 `dimensions[name]=intensity`로 승격해 읽고, Core의 감정
모델이 확장될 때 다차원 값을 직접 받는다.

### Character State

`idle`, `listening`, `thinking`, `speaking`, `reacting`, `executing_action`을 최상위
대화/행동 phase로 사용한다. Emotion, Attention, Gesture, Posture는 phase의 하위
상태가 아니라 병렬 layer다.

### Emotion Target

- `dimensions`: 이름별 0..1 강도
- `valence`, `arousal`, `dominance`: 선택적인 -1..1 연속값
- `blendSeconds`, `holdSeconds`: 표현 전환 힌트
- Backend는 감정 의미를 결정하고 Unreal profile은 morph/material/posture 가중치를
  결정한다.

### Attention Target

- `kind`: `user`, `world_object`, `point`, `none`
- `targetId`: 안정적인 semantic ID
- `worldPosition`: 관측 가능한 경우의 좌표
- `priority`, `expiresAt`, `headWeight`, `eyeWeight`
- 실제 eye/head 각도와 보간은 Unreal이 계산한다.

### Gesture Intent

- `semantic`: `thinking`, `explain_small`, `acknowledge` 같은 의미
- `intensity`, `handPreference`, `durationHintMs`
- `constraints`: 앉음/서기, 손 점유, interruptible 여부
- Unreal `GestureDefinition` DataAsset이 조건에 맞는 animation 후보를 선택한다.
- Backend는 Animation Asset ID를 보내지 않는다.

RuntimeCore의 `GestureRuntime`은 위 정의를 엔진 비종속 데이터로 받아 semantic,
현재 posture, intensity, cooldown, priority와 interruptible 여부로 후보를 제한한다.
같은 seed와 event 순서는 같은 variant를 선택하므로 테스트와 캡처가 재현 가능하다.
새 대화 generation은 이전 generation 제스처만 blend-out하고 generation이 없는 ambient
gesture와 secondary motion은 유지한다.

### Character Profile DataAsset

```text
GahyeonCharacterProfile
├─ EmotionProfile
├─ GestureSet
├─ PostureSet
├─ AttentionProfile
├─ SecondaryMotionProfile
└─ VoiceVisemeProfile
```

### Interaction Point

World Object에는 `UGahyeonInteractionPointComponent`를 배치한다. 컴포넌트의 World
transform은 캐릭터 root/발이 정렬될 정확한 위치와 방향이며 다음 데이터만 가진다.

- `semanticId`: World 내 유일한 안정 ID (`bed`, `desk`, `bookshelf`, `chair`, `window`, `room-center`)
- `roomId`: Core Home World와 동일한 안정 room ID
- `supportedActivities`: 해당 지점에서 가능한 의미 행동 (`work`, `sit`, `read`, `sleep`)

World-local registry는 중복 ID를 거부한다. `world.transition.target.interactionTarget`이
존재하지 않거나 room이 다르거나 요청 activity를 지원하지 않으면 임의의 근처 Actor를 선택하지 않고
`failed/interaction_point_invalid`로 완료한다. 등록된 지점이 있으면 Backend가 보낸 대략
위치보다 로컬 component transform을 우선해 NavMesh 도착과 montage root alignment를
일치시킨다. Animation asset 선택은 Character Profile/DataAsset이 담당한다.

`FGahyeonInteractionPresentationDefinition`은 `(activity, interactionTarget)`을 local
Montage와 결과 posture에 매핑한다. target-specific 정의가 activity-wide fallback보다
우선하며 동일 key 중복은 profile 검증 실패다. profile 적용 시 gesture/interaction soft
asset을 비동기로 preload한다. 실행 경로에서는 `LoadSynchronous`를 금지해 캐릭터가 행동
시작 순간 Game Thread asset load로 멈추지 않게 한다.

## 세 계층의 책임

| 계층 | 예산 | 입력 | 출력 | LLM 의존 |
|---|---:|---|---|---|
| Reflex | 16~100ms | VAD, face pose, sound, pointer | look-at, blink 억제, startle | 없음 |
| Behavior | 100~500ms | phase, emotion, intent, reflex | posture, gesture 후보, blend | 없음 |
| Cognition | 500ms~수초 | transcript, memory, world context | response, intent, long state | 있음 |

세 계층은 순차 pipeline이 아니다. Reflex와 Behavior는 Cognition 요청이 진행 중이어도
계속 평가되며, Presentation은 모든 계층의 최신 유효 intent를 합성한다.

모든 intent가 공통으로 가져야 할 metadata:

- `intentId`, `sourceLayer`, `createdAtMonotonic`
- `priority`, `expiresAfterMs`
- `correlationId`, 선택적 `generation`
- `interruptible`, `blendInMs`, `blendOutMs`

`generation`은 사용자가 새 발화를 시작하는 등 대화 turn이 바뀔 때 증가한다. 이전
generation의 늦은 LLM/TTS 결과는 화면과 음성에 적용하지 않는다.
호흡·눈깜빡임·미세 자세처럼 대화와 무관한 ambient intent는 generation을 갖지 않으며
turn이 바뀌어도 계속 합성된다.

우선순위는 안전/강제 interruption > Reflex > 명시적 speaking/listening > ambient idle
순으로 둔다. Reflex는 짧은 overlay이며 영속 World State를 임의로 변경하지 않는다.

## 상태 전이

```text
Idle ── VAD start ──► Listening ── VAD end ──► Thinking
 ▲                         │                       │
 │                         └─ cancel ──────────────┤
 │                                                 ▼
 └──── response end ◄──── Speaking ◄──── audio playback start

Any ── salient local event ──► Reacting ── timeout ──► previous phase
Any ── action target ─────────► ExecutingAction ─────► previous/Idle
```

`ExecutingAction`은 Backend 정본 World State의 선행 변경이 아니다. 목표 revision을 가진
durable action target과 Core execution due가 먼저 생긴다. Core의 Headless Behavior
executor가 due 시점에 완료하거나 Renderer가 실제 도착을 더 일찍 보고하면 Backend가
하나를 원자적으로 claim·commit한다. 이후 replay/snapshot이 들어와야
room/position/activity가 확정된다. 중복 target/completion은 action ID로 무해하게 만들고,
stale revision·timeout·새 generation 취소는 각 실행 계층의 행동 소유권만 해제한다.

Backend ledger 상태는 `PENDING → PROCESSING → COMPLETED|FAILED|CANCELLED|CONFLICT`다.
source position, target, execution due도 저장한다. world별 활성 slot은 하나만 허용하며
Core executor/Renderer 중 승자가 수행하는 `PROCESSING` claim과 World commit은 한
transaction으로 묶는다. 프로세스 재시작 후 `PENDING`은 같은 action ID와 due로 복원되고,
만료된 `PROCESSING`은 현재 World revision/target을 대조해 이미 commit됐는지 판정한다.

`cognition.response.completed`만으로 Speaking에 진입하지 않는다. 응답 텍스트가 나온 뒤
TTS가 재생 가능한 오디오/viseme을 준비한 `speech.prepared`는 먼저 generation-aware
queue에 들어간다. 현재 generation의 첫 오디오가 실제 playback을 시작할 때만
Speaking으로 전환한다.

각 준비 오디오는 `utteranceIndex`와 문장 내부 `segmentIndex`를 가진다. 전체 응답의
마지막은 `finalSegment`로 추측하지 않고 `speech.sequence.ended`로 확정한다. Renderer는
sequence end를 받은 뒤 ordered queue까지 비었을 때만 정상 발화 완료로 판단한다.

각 segment의 선택적 viseme cue는 semantic, audio-relative `atMs`, `durationMs`, weight를
가진다. Presentation profile이 semantic을 실제 MetaHuman curve/morph에 매핑한다. cue가
없으면 audio RMS 기반 jaw fallback을 사용하며 Emotion 표정과 입 모양은 독립 layer로
합성한다.

Backend 지연 또는 연결 단절 시 `Thinking`에 고정하지 않는다. timeout 후 로컬 ambient
behavior로 전환하되 연결 복구 시 authoritative target으로 수렴한다.

`Thinking`은 캐릭터 전체를 정지시키는 상태가 아니라 표정·시선·자세에 적용되는
Behavior 힌트다. 호흡, 눈 깜빡임, saccade, weight shift와 환경 반응은 계속된다.
