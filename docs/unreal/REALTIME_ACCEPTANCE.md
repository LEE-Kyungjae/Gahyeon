# 저지연 런타임 Acceptance

그래픽 자산 품질과 별개로 아래 항목을 먼저 통과해야 한다. 측정은 monotonic clock을
사용하고 p50/p95/p99와 worst case를 함께 기록한다.

RuntimeCore의 `LatencyTrace`는 metric별 최근 2,048개 sample만 ring으로 유지하고 total
count/budget violation은 누적한다. pending span도 256개로 제한해 누락 callback이 메모리
증가로 이어지지 않는다. 표에 적힌 “반응”은 intent 게시나 stop 요청 시점이 아니라
Anim/Presentation 또는 audio device가 실제 적용을 확인한 시점이다.

| ID | 시나리오 | 합격 기준 |
|---|---|---|
| RT-01 | Backend 없이 30분 idle | frozen state 0회, reflex/secondary motion 지속 |
| RT-02 | VAD start | Listening 시각 반응 p95 ≤ 100ms |
| RT-03 | LLM 10초 지연 | Reflex/Behavior update 누락 0회 |
| RT-04 | 응답 순서 역전 | 이전 generation LLM/TTS 적용 0회 |
| RT-05 | Speaking 중 barge-in | Listening 표현 p95 ≤ 100ms, audio stop p95 ≤ 150ms |
| RT-06 | WebSocket 단절/복구 | game thread stall 0, snapshot으로 2초 내 수렴 |
| RT-07 | malformed/unknown event | 해당 event만 거부, runtime 계속 동작 |
| RT-08 | 10분 speech loop | audio/viseme offset p95 ≤ 80ms |
| RT-09 | Cognition timeout | 로컬 Idle 복귀, 이전 LLM/TTS 적용 0회, Backend task 취소 |
| RT-10 | user target 이동/소실 | Eye 반응 ≤100ms, Head follow, stale target smooth fade |
| RT-11 | VAD end → batch STT final | p95 ≤ 3,000ms, stale generation 적용 0회 |
| RT-12 | World action 수행 | NavMesh/interaction 완료·실패 1회 보고, stale callback 적용 0회 |
| RT-13 | 느린 보조 renderer | Desktop 전달 지연 0, renderer별 순서 보존, 포화 renderer만 격리 |

RT-12의 Backend 경계는 World별 실행 ownership을 구분한다. renderer가 연결된 동안에는
Headless 예상시간 완료를 금지하고 실제 도착/실패 회신을 기다린다. 연결이 끊기면 영속된 pending
action을 Headless가 이어받으며, 사용자 대화가 시작되면 pending 자율 action을 취소한 뒤
Conversation 상태를 적용한다. 늦은 renderer 완료는 terminal action ID로 중복 처리되어야 한다.

Backend message 처리 시간은
`gahyeon.unreal.websocket.message.processing{type}`으로 측정한다. VAD 시각 반응은
Unreal 로컬 monotonic trace로 측정하며 Backend metric으로 대체하지 않는다.
요청 수락부터 첫 모델 텍스트와 첫 발화 가능 문장까지는 각각
`gahyeon.unreal.cognition.first.delta`, `gahyeon.unreal.cognition.first.sentence`로
측정한다. 첫 재생 가능 오디오까지는 `gahyeon.unreal.tts.first.segment`, 개별
세그먼트 합성 시간은 `gahyeon.unreal.tts.segment`로 측정한다. 동기 fallback에서는
first-delta가 전체 모델 응답 시간과 같아지므로 실제 streaming 적용 여부도 계측으로
드러난다. 새 generation으로 취소된 stale cognition 수는
`gahyeon.unreal.cognition.cancelled`로 확인한다.
립싱크 timeline 출처는 `gahyeon.unreal.viseme.timeline{source=provider|heuristic|amplitude}`로
분리하며 RT-08 exact timing 합격에는 `provider` sample만 사용한다.
Aligner 호출 자체는 `gahyeon.unreal.viseme.alignment{source,result}` timer로 측정하며 result는
`success|empty|audio_invalid|contract_invalid|failure`의 고정 집합이다. 따라서 amplitude fallback이
정상 무음/빈 timeline인지 provider timeout·digest·cue 계약 실패인지 운영 중 구분할 수 있다.

VAD start는 `perception.voice.started`가 Backend에 도착하는 즉시 session generation을
전진시켜 실행 중인 이전 Cognition Future를 취소한다. Thinking watchdog이 만료되면
RuntimeCore가 자체적으로 새 generation과 Idle을 만들고
`interaction.generation.advanced{reason=cognition_timeout}`를 보내 같은 취소를 수행한다.
발화 중 마이크 장치·권한·Client capture가 종료되어 VAD end를 받을 수 없을 때는 잘린 음성을
commit하지 않고 `interaction.generation.advanced{reason=microphone_capture_aborted}`를 보낸다.
Backend는 이 reason도 timeout/STT failure와 같은 generation barrier로 받아 현재 Cognition과
TTS를 즉시 취소해야 한다.
Stage의 Behavior cadence가 watchdog을 직접 advance하며, timeout 시 pending STT/latency span,
gesture, speech, World action generation을 같은 Game Thread 단계에서 함께 무효화한다.
Backend도 같은 generation advance를 Cognition과 TTS admission에 동시에 적용한다. 대기 중인
TTS는 provider 호출 전에 폐기하고, 실행 중 Future에는 interrupt cancellation을 요청하며,
합성 후 publish 전에 stale이 된 audio cache entry는 즉시 제거한다.
Backend audio cache는 5분 TTL과 별개로 최대 256개, 개별 32 MiB, 총 256 MiB를
동시에 강제한다. 한도를 넘으면 가장 오래된 entry부터 제거하므로 renderer가 URL을
가져가지 않거나 많은 session이 동시에 말해도 heap 사용량이 무한히 증가하지 않는다.
현재 사용량은 `gahyeon.unreal.audio.cache.entries`와
`gahyeon.unreal.audio.cache.bytes`, 자동 회수는
`gahyeon.unreal.audio.cache.evicted{reason=capacity|expired}`, 과대 결과 거부는
`gahyeon.unreal.audio.cache.rejected{reason=entry_too_large}`로 관측한다.
동일 session에 여러 renderer가 연결될 수 있으므로 한 socket 종료만으로 공유 작업을 취소하지
않는다. 마지막 subscriber가 종료된 경우에만 queued/running Cognition·TTS를 취소하고
process-local admission/VAD lifecycle 상태와 latest perception/generation watermark를 즉시
해제하여 장기 실행 누적을 막는다. Store는 hello가 완료된 활성 session의 event만 받으므로
종료와 경합한 늦은 STT/pose callback이 정리된 상태를 다시 만들 수 없다.
Backend outbound는 renderer별 bounded serial queue로 분리한다. slow consumer가 queue 한도를
소진하면 그 renderer만 제거하고 cleanup을 한 번 실행하며, 건강한 renderer와 publish caller는
막지 않는다. 자동화 검증은 느린 Looking Glass를 latch로 정지시킨 상태에서 Desktop 수신,
100개 event의 renderer별 순서, queue 포화 시 단일 cleanup을 각각 확인한다.
수락된 renderer 전달 수는 `gahyeon.unreal.outbound.admitted`, 격리는
`gahyeon.unreal.outbound.renderer.detached{reason=queue_full|executor_rejected|delivery_failed}`로
관측한다.
장시간 운용 중 연결 해제 누수는 `gahyeon.unreal.outbound.renderers`,
`gahyeon.unreal.outbound.sessions`, `gahyeon.unreal.outbound.queued`,
`gahyeon.unreal.client.bindings`, `gahyeon.unreal.client.sessions`,
`gahyeon.unreal.perception.lifecycle.sessions`,
`gahyeon.unreal.perception.latest.sessions` gauge로 분리해 관측한다. 마지막 renderer 종료 후
해당 session의 binding, outbound queue, perception lifecycle/latest 값은 모두 0으로 돌아가야 한다.
`FGahyeonRuntimeFrameSnapshot.CognitionTimeoutCount`와
`LastCognitionTimeoutGeneration`은 PIE overlay와 자동화에서 발동 증거로 사용한다.

재접속 중 로컬보다 높은 durable generation을 처음 적용할 때는 intent watermark와
speech queue를 한 단계에서 함께 전진시킨다. 그 단계가 반환한 기존 utterance를 즉시
정지하며, 이후 도착하는 낮은 generation state/audio callback은 모두 거부한다.
반대로 reconnect/프로세스 재시작이 local generation을 0으로 되감아서도 안 된다.
SaveGame v2는 interaction generation watermark를 cursor/action과 함께 저장하며, 기존 v1은
generation 0으로 마이그레이션한다. 같은 프로세스 reconnect에서는 메모리와 디스크 중 큰
watermark를 복원하고 Character, speech/lip-sync, gesture, World action에 원자적으로 적용한다.

SaveGame restore가 RuntimeCore instance를 교체하는 경계에서는 WebSocket callback도 runtime
epoch에 묶는다. 이전 epoch에서 parse가 시작된 message나 늦은 `OnConnected`는 새 runtime에
적용하지 않으며, socket을 재연결해 persisted cursor부터 다시 수렴한다.
이전 connection-generation callback은 현재 socket에 영향을 주지 않는 no-op이고, 현재
connection-generation의 runtime epoch mismatch만 재연결 원인이 된다.

복원 전후의 generation 값이 같더라도 비동기 작업의 소유권은 같지 않다. Stage는 설치된
RuntimeCore마다 process-local runtime epoch를 증가시키고, 이전 epoch에서 시작된 STT HTTP
응답·PCM 조립 결과·관측 큐와 TTS 다운로드/장치 재생을 폐기한다. 따라서 SaveGame 복원이
generation을 보존하는 경우에도 이전 RuntimeCore의 늦은 callback은 새 상태에 적용되지 않는다.
같은 durable world action id가 replay되는 경우도 runtime epoch가 다른 이전 asset-load/Montage
종료 callback을 거부하여, 과거 애니메이션이 새 액션을 완료 처리할 수 없게 한다.

RT-06 시간은 WebSocket open 시도의 local monotonic timestamp부터 hello 직후
`world.snapshot` 전체가 `WorldStateRuntime`에 원자 적용된 시점까지다. snapshot 이후
도착한 낮은 World revision replay는 수렴을 깨지 않으며, 2초를 넘기면
`ConnectionConvergenceRuntime`이 timeout을 노출한다.
Stage는 이 timeout을 관측하면 socket이 열려 있더라도 Backend connected 상태를 즉시 내리고
transport reconnect를 요청한다. 누적 횟수는
`FGahyeonRuntimeFrameSnapshot.ReconnectConvergenceTimeoutCount`로 확인한다.
최초 authoritative snapshot은 deadline과 순서를 강제하지만, 한 번 수렴한 뒤의
revision-aware duplicate/replay snapshot은 정상적으로 적용 또는 무시하며 재접속 원인으로
취급하지 않는다.
재접속이 결정되는 순간에는 이전 socket에서 이미 들어온 deferred/queued envelope를 새 연결이
restore되기 전에 모두 폐기한다. producer와 reset은 짧은 network-mailbox lock으로 직렬화하고,
폐기 수는 `ReconnectDiscardedInboundEvents`에 누적한다. audio capture callback은 이 lock을
사용하지 않는다.

전체 inbound mailbox는 1,024개지만 교체 가능한 attention/gesture/Cognition lifecycle
latest-state 입력은 768개에서 먼저 drop하여 256개를 speech, connection control,
durable/command replay에 예약한다. latest-state drop은 counter에 남기되 socket을 닫지 않고,
speech/control/durable/command가 전체 상한을 넘을 때만 persisted cursor replay를 위해
재접속한다.

RT-08은 message 수신 시각이 아니라 audio device가 보고한 segment playback position과
viseme cue의 `atMs` 차이를 측정한다. timeline이 없는 amplitude fallback은 sync offset
통계와 구분한다. RuntimeCore의 10분/60 segment 가상 playback loop는 cue onset worst
case 32ms 이하와 누적 clock drift 0을 검증한다.

Emotion blend와 Gesture selection은 Behavior budget 안에서 Game Thread local data만
사용한다. Cognition 지연 중에도 emotion interpolation과 gesture expiry/cooldown은 계속
진행하며, 같은 seed/profile/event 순서의 variant 선택은 재현 가능해야 한다.

## 선행 테스트 묶음

- deterministic virtual clock
- 0.5/2/5/10초 Cognition delay
- timeout, connection loss, duplicate, missing, reordered event
- 새 generation 직전/직후 도착하는 LLM과 TTS
- VAD false start와 빠른 speech interruption
- 60Hz와 저프레임 환경에서 intent expiry
- 동일 seed/timestamp secondary motion 재현, 30분 동안 NaN/범위 이탈 0회
- 10,000개 latency sample 후 retained ring capacity 고정, p50/p95/p99 nearest-rank 검증
- 누락/중복 span, pending saturation, monotonic clock rewind에도 음수 sample 0회
- STT 응답 전 barge-in 시 이전 generation transcript 적용 0회
- 30초 초과 PCM의 bounded truncation, format change utterance 제출 0회

현재 TypeScript 참조 arbiter는
[`../../desktop/src/runtime/intent-runtime.ts`](../../desktop/src/runtime/intent-runtime.ts)에
있다. Unreal C++ 구현은 같은 fixture와 결과를 사용하되 코드를 직접 공유할 필요는
없다.

VAD → Listening → Thinking → Speaking 및 barge-in generation 동작의 참조 구현은
[`../../desktop/src/runtime/realtime-character-coordinator.ts`](../../desktop/src/runtime/realtime-character-coordinator.ts)에
있다. RMS hysteresis/attack/release 참조 구현은
[`../../desktop/src/runtime/voice-activity-detector.ts`](../../desktop/src/runtime/voice-activity-detector.ts)에
있으며 C++ RuntimeCore와 같은 경계 fixture를 사용한다.

요구사항별 현재 증거와 UE 실기기에서 남은 검증은
[`ACCEPTANCE_STATUS.md`](ACCEPTANCE_STATUS.md)에서 추적한다.

## Stage latency instrumentation

`UGahyeonRuntimeSubsystem`은 bounded `LatencyTrace` 하나를 소유하고 다음 실제 adapter
callback에서 span을 닫는다.

- local VAD edge → Presentation이 Listening frame을 실제 적용한 tick
- barge-in generation → AudioComponent가 이전 device audio를 실제 stop한 직후
- connection attempt → welcome 이후 authoritative `world.snapshot` 적용
- final transcript 제출 → Thinking frame 적용 및 첫 audio device playback 시작
- provider viseme timestamp → Presentation이 해당 cue의 morph/Control Rig 적용을 확인한 시점

각 summary의 sample count, p50, p95, p99, worst, budget, violation count는
`FGahyeonRuntimeFrameSnapshot`과 debug overlay에 노출한다. sample이 0개인 지표는 합격
증거가 아니며 `bPassesP95=false`로 유지한다. `NotifyFinalTranscriptSubmitted()`는 실제 STT
command enqueue 성공 직후 호출해야 하며, 호출하지 않은 mock 경로의 수치는 만들지 않는다.
Batch fallback의 VAD end→final transcript는 capture timestamp부터 generation-bound final이
Unreal Runtime에 돌아온 시점까지 측정한다. HTTP 실패·빈 transcript·provider 오류는 성공
sample로 기록하지 않고 failure counter와 span cancellation로 분리한다. Batch STT와 speech
audio 다운로드는 각각 8초 hard timeout으로 half-open 요청을 제한한다. STT 실패 시 watchdog을
기다리지 않고 즉시 새 generation의 Idle로 복귀하며 `stt_failed` generation advance로 Backend의
이전 Cognition/TTS도 취소한다.

같은 overlay는 현재 WebSocket 상태, pong 대기 여부, monotonic clock으로 측정한 최근
heartbeat RTT와 누적 accepted/timeout/invalid/stale pong 횟수를 표시한다. 연결이 종료되면
과거 RTT를 현재 연결의 값처럼 보이지 않도록 `unknown`으로 초기화하지만 장애 counter는
프로세스 동안 유지해 간헐적인 half-open과 protocol 오류를 확인할 수 있게 한다.

## Packaged Desktop 10분 측정

일반 single-view packaged Development 실행에는 다음 인자를 준다. 출력 경로는 기존 파일을
덮어쓰지 않으며 `-GahyeonRtExit`가 있으면 원자적 JSON 저장 성공 뒤에만 종료한다.

일반적인 실행은 packaged 폴더에서 정확히 하나의 `GahyeonStage.exe`를 찾아 runtime log,
raw, acceptance를 한 evidence 디렉터리에 남기는 runner를 사용한다.

```powershell
.\scripts\run_desktop_realtime_acceptance.ps1 `
  -PackagedRoot "C:\gahyeon-package" `
  -EvidenceRoot "C:\gahyeon-evidence\desktop-0001" `
  -DurationSeconds 600
```

`-RequirePassed`를 추가하면 실행 중 각 물리 반응을 최소 20회 발생시켜 세 latency gate까지
통과해야 명령이 성공한다. 이 옵션이 없으면 cadence/rendering 자료는 보존하되 상호작용 표본이
부족한 결과를 정직하게 `measured`로 허용한다.

```powershell
GahyeonStage.exe `
  -GahyeonRtRunId=desktop-1660ti-0001 `
  -GahyeonRtDuration=600 `
  -GahyeonRtOutput=C:\gahyeon-evidence\raw-desktop.json `
  -GahyeonRtExit -windowed -ResX=1920 -ResY=1080
```

`UGahyeonRealtimeBenchmarkComponent`는 명령행 opt-in일 때만 tick하며 frame time,
Reflex/Behavior update 수와 최장 정지 구간, 실제 presentation callback에서 나온 세 latency
배열을 기록한다. 결과는 다음처럼 집계한다.

```bash
python3 scripts/build_desktop_realtime_acceptance.py \
  --raw path/to/raw-desktop.json \
  --output path/to/desktop-acceptance.json
python3 scripts/verify_desktop_realtime_acceptance.py \
  path/to/desktop-acceptance.json --require-passed
```

최소 10분·초당 10 frame sample, frame p95 33.34ms/p99 50ms, dropped frame 2% 이하,
Reflex 250ms/Behavior 750ms 초과 정지 없음이 cadence/rendering gate다. VAD→Listening,
barge-in→audio stop, audio→viseme는 각각 최소 20개 물리 표본이 있어야 최종 `passed`가 되며,
표본이 부족한 idle-only 실행은 `measured`로 남는다.
verifier는 acceptance의 요약값을 신뢰하지 않고 checksum으로 묶인 raw frame/latency 배열에서
p95/p99와 drop 비율을 다시 계산한다. 따라서 raw는 그대로 둔 채 summary나 pass flag만 낮춰
쓰는 결과도 거부된다.
