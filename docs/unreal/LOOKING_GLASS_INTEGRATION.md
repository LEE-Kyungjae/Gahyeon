# Looking Glass Go 통합 계획

Looking Glass Go는 별도 Gahyeon 인스턴스가 아니라 Desktop/Unreal Stage와 같은 session,
World State, speech sequence를 구독하는 선택형 display renderer다.

## 도구별 판단

| 도구 | Gahyeon에서의 역할 | 지금 도입 여부 |
|---|---|---|
| Looking Glass Bridge | 장치 탐색·calibration·출력 연결 | 실제 Go 검증 머신에 필수, 일반 실행에는 불필요 |
| Unreal Plugin 2.1.1 | UE 5.6 scene의 실시간/적응형/정적 quilt 출력 | prototype 대상으로 확정, 배포 기본 비활성 |
| Model Viewer | G1~G5 FBX/GLB의 깊이·실루엣 빠른 육안 검수 | 제작 QA 보조로 권장 |
| Blender Add-on | blockout/animation의 holographic preview와 pre-render | 선택형 authoring QA |
| Looking Glass Studio | quilt/image/video 재생과 장치 콘텐츠 관리 | 데모·납품 검수용 |
| Unity Plugin | 공식 지원되는 실시간 비교 baseline 또는 저사양 fallback 연구 | Unreal 측정 실패 전에는 본선화하지 않음 |
| WebXR Library | 브라우저 기반 보조 viewer 실험 | 현재 범위 밖 |

## 현재 제한

공식 저장소 최신 release 2.1.1은 UE 5.6 지원을 명시한다. 소스에는 `Realtime`,
`RealtimeAdaptive`, `NonRealtime` 성능 모드가 있고, 런타임 기본 경로는 매 frame quilt를
렌더링해 Bridge에 DirectX texture로 전달한다. 기본 quilt 설정은 11×6, 즉 66 view다.
그러나 README는 현재 플러그인을 실시간 콘텐츠 생성용으로 의도하지 않았다고 명시하고 plugin
descriptor는 Win64만 허용한다. 따라서 **실시간 구현은 존재하지만 목표 하드웨어에서의 제품성은
미검증**이다. 공식 다운로드 페이지의 2.1.0 설명보다 GitHub 2.1.1 release가 최신 호환성 근거다.

## 활성화 순서

1. Win64 UE 5.6 제작 머신에 Bridge 2.5.1 이상과 Plugin 2.1.1을 설치한다.
2. 별도 prototype map에서 Go 장치 탐색과 calibration을 확인한다.
3. 동일 Stage World snapshot을 perspective camera와 Looking Glass capture가 동시에 소비한다.
4. Bridge/Go를 끈 상태에서 일반 renderer와 Core health가 유지되는지 확인한다.
5. `Realtime`, `RealtimeAdaptive`, `NonRealtime` 세 모드에서 idle, listening, thinking,
   speaking 각각 GPU frame time과 frame pacing을 측정한다. 66-view 기본값과 낮춘 quality
   profile을 분리 기록한다.
6. VAD→Listening, barge-in→audio stop, audio→viseme 기존 latency budget이 악화되지 않는지
   확인한다.
7. 합격할 때만 opt-in 배포 profile에 Plugin을 추가한다. prototype build에서는 즉시 시험할 수
   있지만 기본 Desktop profile에는 넣지 않는다.

공식 prebuilt archive는 release URL, byte size, SHA-256과 내부 Win64 module descriptor까지
검증한 뒤 `Plugins/LookingGlass`에 원자적으로 설치한다. 기존 plugin 디렉터리는 자동으로
덮어쓰지 않으며 canonical `.uproject`의 활성화 여부도 변경하지 않는다.

```bash
python3 scripts/install_looking_glass_unreal_plugin.py \
  --project unreal/GahyeonStage/GahyeonStage.uproject \
  --download
```

설치 후 Windows 제작 머신의 Editor에서 prototype용으로만 LookingGlass plugin을 켜고
Development Editor build를 수행한다. 설치 디렉터리는 third-party 생성물이라 Git에 포함하지
않는다.

기본 실행은 `GahyeonStage.uproject`, Go 실험은
`GahyeonStageLookingGlass.uproject`를 사용한다. 두 descriptor는 같은 `GahyeonStage` module과
Core plugin set을 사용하고 후자만 Win64 한정 `LookingGlass` plugin과
`GahyeonLookingGlassAdapter` module을 활성화한다. Adapter는 plugin 공개 API를
`GahyeonStage`의 plugin-neutral modular feature 경계로 변환하며 canonical Stage의 build
dependency에는 포함되지 않는다. 따라서 Go
prototype이 실패해도 일반 Stage project descriptor를 수정하거나 복구할 필요가 없다.

```bash
python3 scripts/verify_looking_glass_unreal_profile.py
```

Windows 제작 머신에서는 설치 뒤 다음 gate가 UE 5.6 Development Editor build와 전체 Gahyeon
Automation을 실행하고, plugin이 실제 활성화되어 `LookingGlassRuntime` module을 제공하는지도
별도 Automation test로 확인한다. 이 명령의 `-NullRHI` 단계는 compile/plugin-load 증거이며
실제 Go 출력 성능 증거를 대신하지 않는다.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run_looking_glass_windows_gate.ps1 `
  -UnrealRoot "C:\Program Files\Epic Games\UE_5.6"
```

기계 검증은 다음 lock contract가 담당한다.

```bash
python3 scripts/verify_looking_glass_integration.py
```

실기기 측정은
[`gahyeon-looking-glass-acceptance-v1.schema.json`](../contracts/gahyeon-looking-glass-acceptance-v1.schema.json)에
기록한다. idle/listening/thinking/speaking을 각 60초 이상 측정하고 raw CSV/trace를 checksum으로
봉인한다. `passed`는 NonRealtime 결과가 좋아서는 받을 수 없으며, Realtime 또는
RealtimeAdaptive profile 하나가 30fps frame pacing과 기존 VAD 100ms, audio stop 150ms,
viseme onset 80ms 반응 예산을 모두
통과해야 한다.

```bash
python3 scripts/verify_looking_glass_acceptance.py \
  path/to/looking-glass-acceptance.json --require-passed
```

집계값은 수기로 작성하지 않는다. 측정 JSON에는 각 profile/scenario의 원시 `frameMs`,
`vadToListeningMs`, `bargeInToAudioStopMs`, `audioToVisemeMs` 배열을 저장한다. frame은 60초당
최소 600개, 각 반응 지표는 최소 20개가 있어야 하며, 다음 도구가 nearest-rank p95/p99와
dropped-frame 비율을 계산하고 원본 전체를 evidence로 봉인한다.
모든 UE fragment와 병합 raw, 최종 acceptance에는
`latencyBoundary=physical-presentation-v1`이 있어야 한다. 이는 audio cursor가 cue를 계산한
시점이 아니라 Presentation의 morph/Control Rig 적용 확인에서 수집한 표본임을 뜻한다. 이전
cursor-only fragment를 섞거나 이 표식이 없는 raw 파일을 재사용하면 merge/build가 실패한다.
이 표식만 수기로 추가해도 통과하지 않는다. 각 scenario에는 adapter가 확인한 player/device/
capture 상태와 plugin quilt screenshot PNG의 상대 경로·크기·SHA-256이 들어 있는
`presentationAttestation`이 필요하다. merge/build/final verifier는 PNG IHDR 해상도까지 실제
capture 설정과 대조하고 12개 scenario가 서로 다른 quilt evidence를 가졌는지 검사한다.

```bash
python3 scripts/build_looking_glass_acceptance.py \
  --raw path/to/raw-looking-glass-measurement.json \
  --output path/to/looking-glass-acceptance.json
```

UE 실행 시 다음 인자를 주면 `Saved/GahyeonBenchmarks/<profile>--<scenario>.json`에 raw fragment를
직접 기록한다. recorder는 기본 실행에서 tick하지 않으며, 60초 이상 유효한 측정 인자가 모두
있을 때만 활성화된다. 숫자 인자는 측정값이 아니라 **현재 plugin 설정에 대한 기대값**이다.
Adapter가 physical-device output, Go tiling profile, begun/registered capture, player와 실제 quilt
screenshot을 확인하지 못하거나 기대값과 실제값이 다르면 JSON을 만들지 않는다. 장치 serial은
Bridge/운영자가 metadata에 SHA-256으로 별도 기록하며 plugin 2.1.1 공개 API가 serial을 노출하지
않으므로 adapter attestation과 자동 교차검증되지는 않는다.

```text
-GahyeonLgRunId=go-1660ti-20260812-01
-GahyeonLgProfile=go-realtime-quality
-GahyeonLgMode=Realtime
-GahyeonLgScenario=speaking
-GahyeonLgDuration=60
-GahyeonLgViews=66
-GahyeonLgQuiltWidth=4092
-GahyeonLgQuiltHeight=4092
```

`RunId`와 profile은 소문자 영숫자·`_`·`-`만 허용한다. 모든 fragment와 metadata의 RunId가
같아야 하며, 기존 `<profile>--<scenario>.json`은 덮어쓰지 않는다. 재측정은 새 RunId로
시작해야 서로 다른 실행의 표본이 섞이지 않는다. 각 JSON 옆에는
`<profile>--<scenario>--quilt.png`가 생성되며 이 파일이 없거나 변조되면 이후 gate가 실패한다.

현재 저장소는 이 source/contract 경계까지 제공한다. 실제 합격을 주장하려면 Win64 UE 5.6,
고정 plugin 2.1.1, Bridge 2.5.1 이상, 실제 Looking Glass Go와 scene에 배치된 active
`ALookingGlassCapture`가 필요하다. macOS의 정적 검사나 `-NullRHI` Automation은 이 실기기
증거를 대신할 수 없다.

세 모드 × 네 시나리오의 12개 fragment를 모은 후 장치 serial 원문이 아닌 SHA-256, GPU,
driver, Bridge 버전이 들어 있는 metadata와 병합한다.

```bash
python3 scripts/merge_looking_glass_benchmark_fragments.py \
  --fragments path/to/Saved/GahyeonBenchmarks \
  --metadata path/to/benchmark-metadata.json \
  --output path/to/raw-looking-glass-measurement.json
```
