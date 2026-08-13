# ADR-0010: Looking Glass는 선택형 Renderer다

## Status

Accepted, integration not enabled

## Context

Gahyeon은 일반 모니터에서 완전히 동작해야 하고 Looking Glass Go는 동일한 World와 Avatar
상태를 표현하는 추가 display다. 공식 Unreal Plugin 2.1.1은 UE 5.6을 지원한다. 소스에는
`Realtime`, `RealtimeAdaptive`, `NonRealtime` 모드, standalone/game viewport, 매 프레임
quilt 생성과 Bridge texture 전송 경로가 실제 구현되어 있다. 다만 upstream README는 현재
플러그인을 실시간 콘텐츠 생성용으로 의도하지 않았다고 명시한다. 이는 실시간 기능 부재가
아니라 성능·제품 지원 수준의 제한으로 해석한다. Plugin descriptor의 Runtime/Editor module은
Win64만 허용한다. Bridge 2.5.1 이상과 전용 GPU가 필요하며 실제 device render 비용은 아직
측정하지 않았다.

## Decision

- `Gahyeon Core → semantic event/world state → renderer` 경계를 유지한다.
- Looking Glass Plugin은 `GahyeonStage`의 선택형 Win64 출력 backend로만 평가한다.
- Plugin, Bridge 또는 Go가 없어도 Core, Discord, Desktop, 일반 Unreal perspective renderer는
  정상이어야 한다.
- Go용 별도 LLM, Memory, STT, TTS 또는 Session을 만들지 않는다.
- Plugin 2.1.1과 정확한 upstream commit은 lock contract에 기록하되 source/binary는 아직
  vendoring하거나 기본 활성화하지 않는다.
- Unreal 실시간 prototype은 진행한다. 실제 Go와 목표 GPU에서 frame pacing 및 기존
  Reflex/audio latency budget을 통과한 뒤에만 배포용 opt-in renderer로 활성화한다.

## Consequences

공식 플러그인의 calibration, quilt/light-field 출력과 Bridge 연동을 재사용할 수 있다. 반면
현재 upstream이 실시간 제품 용도를 보증하지 않으므로 이를 주 Desktop renderer로 간주할 수
없다. 그러나 구현된 실시간 모드를 prototype에서 직접 측정할 수 있다. 상호작용 성능이 충분하지
않으면 `RealtimeAdaptive`/낮은 quilt 설정, Unity 기반 보조 renderer, 또는 Bridge SDK 기반 별도
renderer를 비교할 수 있으며 Core 변경은 필요하지 않다.

## Sources

- [Looking Glass Unreal Plugin repository](https://github.com/Looking-Glass/Looking-Glass-Unreal-Plugin)
- [UE 5.6 support release 2.1.1](https://github.com/Looking-Glass/Looking-Glass-Unreal-Plugin/releases/tag/2.1.1)
- [Looking Glass Bridge](https://lookingglassfactory.com/software/looking-glass-bridge)
