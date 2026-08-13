# ADR-0009: Streaming STT는 Core 소유의 별도 duplex transport를 사용

- 상태: Accepted
- 결정일: 2026-08-12

## 맥락

현재 Unreal VoiceInput은 capture callback, VAD, bounded PCM sink, partial/final mailbox와
batch WAV fallback을 갖고 있다. 그러나 기본 sink는 VAD 종료 후 전체 WAV를 Core HTTP endpoint로
보내므로 실제 partial transcript를 만들지 않는다. Provider SDK/API key를 Unreal에 직접 넣으면
STT가 Presentation Client에 귀속되고 Desktop/Discord와 provider 교체 경계를 공유할 수 없다.
반대로 기존 World/Behavior event WebSocket에 고빈도 binary audio까지 섞으면 cursor ACK,
barge-in, action completion과 오디오가 같은 queue에서 head-of-line blocking을 일으킬 수 있다.

## 결정

실제 Streaming STT provider와 credential은 Gahyeon Core/Backend가 소유한다. Unreal의
`IGahyeonStreamingSttAudioSink` 구현은 provider가 아니라 Core streaming endpoint로 PCM을
운반하는 adapter다.

- 기존 `/gahyeon/unreal/v1` control/event transport와 별도의 인증된
  `/gahyeon/unreal/stt/v1` duplex STT 연결을 쓴다.
- control frame은 `start`, `end`, `cancel`과 session/generation, capture format, monotonic timestamp,
  순번을 전달한다.
- PCM payload는 binary frame으로 보내고 한 utterance 안에서 순번과 format을 고정한다.
- Backend는 provider별 요구 형식으로 resample/encode하며 Unreal은 provider SDK나 API key를 모른다.
- Backend가 반환한 partial/final에는 원래 session/generation과 provider result sequence가 붙는다.
- Core는 provider partial을 Perception/Behavior에 직접 입장시키고 Unreal에도 돌려보내 bounded
  mailbox를 통해 local Attention/Listening 표현을 갱신한다.
- final만 Conversation/Cognition command가 될 수 있다. Partial은 LLM turn을 시작하지 않는다.
- stale generation, 순번 역행, format 변경, queue overflow는 그 utterance 전체를 취소한다. 중간
  PCM 일부를 조용히 버리고 잘못된 transcript를 만드는 동작은 금지한다.
- 연결 단절 시 현재 generation을 실패 처리하고 batch fallback은 **다음 utterance부터만** 선택한다.
  한 utterance 안에서 streaming과 batch 결과를 경쟁시키지 않는다.
- 기본 batch WAV 경로는 provider 미설정/장애 시 명시적인 fallback으로 유지한다.

## 지연 및 backpressure 계약

- capture callback은 복사/queue offer만 수행하며 socket, UObject, provider future를 기다리지 않는다.
- client PCM queue와 Backend provider queue는 둘 다 bounded다.
- VAD→Listening은 네트워크와 무관하게 기존 Reflex budget 100ms 이내를 유지한다.
- 첫 유효 partial과 final latency는 별도 metric으로 측정한다.
- Partial latest-state coalescing은 허용하지만 final, VAD edge, cancel은 drop할 수 없다.
- provider가 느려 hard bound를 넘으면 generation을 취소하고 캐릭터는 Idle/ambient로 복귀한다.
- 오디오 장치 종료·권한 철회·Client teardown처럼 VAD `end`가 발생하지 않는 중단은 정상 종료로
  위장하지 않는다. 활성 generation에 `cancel`을 보내고 batch buffer, streaming queue, capture
  pre-roll과 대기 중 RMS frame을 폐기한 뒤 VAD를 reset한다. 이미 VAD가 끝나 Cognition으로 넘어간
  utterance는 단순한 마이크 종료로 취소하지 않는다.

## 결과

장점:

- Discord/Desktop/Unreal이 같은 STT provider와 credential 정책을 공유한다.
- 렌더러 교체와 STT 모델 교체가 서로 영향을 주지 않는다.
- 고빈도 PCM이 World/Behavior durable event를 막지 않는다.
- Local Reflex는 Core 왕복이나 LLM 완료를 기다리지 않는다.

비용과 제한:

- 별도 streaming endpoint, provider session adapter, Unreal network sink 구현이 추가로 필요하다.
- provider별 resampling과 partial 안정도 의미를 정규화해야 한다.
- provider-neutral Core port, 엄격한 sequence/backpressure lifecycle, 인증된 WebSocket endpoint,
  event-socket session binding과 Core direct admission은 구현됐다.
- Unreal에는 preallocated SPSC capture ring, bounded RuntimeCore egress lifecycle, 별도 authenticated
  `IWebSocket` client, reconnect/backoff, partial/final generation gate와 next-utterance batch fallback이
  구현됐다. opt-in 기본값은 꺼져 있다.
- 실제 Backend provider adapter와 UE 5.6 compile/PIE 증거가 아직 없으므로 기본값은 여전히 batch
  WAV다. 이 ADR과 transport 구현만으로 Streaming STT 운영 완료를 주장하지 않는다.

## 최초 실제 provider 기준선

OpenAI 공식 Realtime transcription 규격을 최초 호환 기준선으로 삼는다. 이 선택은 Core port를
OpenAI에 결합한다는 뜻이 아니며 adapter 하나의 wire mapping만 정의한다.

- `gpt-live-transcribe`, transcription session, server-side WebSocket을 우선 검증한다.
- Unreal의 `float32le`은 Backend에서 mono PCM16 24 kHz로 변환한다.
- provider에는 `input_audio_buffer.append`로 audio를 보내고 VAD end에서
  `input_audio_buffer.commit`을 보낸다.
- provider `delta`는 누적 partial로, `completed`는 단 한 번의 final로 정규화한다.
- 공식 API가 confidence를 제공하지 않으므로 가짜 confidence를 만들지 않는다. 내부 stability는
  별도의 `unknown` 의미를 추가하기 전까지 0으로 둔다.
- 초기 delay는 `low`로 두되 실제 한국어 마이크 eval에서 minimal/low/medium을 비교한다.
- 문서상 서로 다른 turn의 completion 순서는 보장되지 않으므로 provider `item_id`와 Core stream을
  매핑한다. 한 provider connection에서 여러 Gahyeon utterance를 섞지 않는 것이 초기 구현이다.

참조: https://developers.openai.com/api/docs/guides/realtime-transcription,
https://developers.openai.com/api/docs/guides/realtime-websocket

## 기각한 대안

- Unreal에서 provider API 직접 호출: credential 노출과 Client 종속 때문에 기각한다.
- 기존 event WebSocket에 PCM 혼합: control/durable traffic의 지연 격리가 깨진다.
- Partial마다 LLM 실행: 비용, 취소 경쟁, 불안정 transcript로 인한 잘못된 turn 생성 때문에 기각한다.
- Streaming과 batch를 동시에 race: 중복 final과 generation ownership 모호성 때문에 기각한다.
