# Gahyeon Stage

Source-only Unreal Engine 5.6 project for Gahyeon's real-time presentation client.

This is intentionally an architecture slice, not a graphics demo. The current native subsystem:

- advances presentation every frame;
- advances Reflex at a 50 ms cadence and Behavior at a 200 ms cadence;
- sends `client.ping` on the server-advertised heartbeat cadence, consumes correlated
  `server.pong` replies inside the transport, exposes the latest application RTT, and reconnects
  a half-open socket when a full heartbeat interval passes without its pong
  generation, and stops it before disconnect/reconnect so idle Stage sessions remain healthy without
  letting an old socket write into its replacement;
- continues both cadences without a Backend connection;
- accepts normalized inbound events through a bounded MPSC queue;
- drains at most 64 inbound events per frame on the Game Thread;
- restores a versioned cursor/action state before opening its WebSocket;
- validates protocol envelopes at the callback boundary and only then schedules mailbox ingress;
- serializes async SaveGame writes so persistence confirmations cannot overtake each other;
- consumes RuntimeCore from the same 26 C++ sources as the standalone test harness;
- emits cursor ACKs and action completions only after the matching async save succeeds;
- restores persisted action retries and persists terminal acknowledgement removal/dead-letter state;
- strictly decodes and applies all canonical World/Emotion/Action/Cognition durable fixtures
  before cursor completion;
- strictly decodes speech, sequence-end, generation, gesture, and attention ephemeral payloads;
- preserves audio URL/MIME/viseme data and exposes audio-device start/finish/failure callbacks;
- broadcasts immediate interruption when a newer generation revokes active playback;
- advances the 10-second local Cognition watchdog on the independent Behavior cadence and
  invalidates pending speech, STT, gestures, World actions, and latency spans together;
- includes a bounded HTTP PCM16 WAV loader and procedural AudioComponent device boundary;
- resolves relative audio URLs against the WebSocket's same HTTP(S) origin and credential;
- auto-spawns one transient presentation host in Game/PIE worlds, but never on dedicated servers;
- exposes ambient/attention/phase/emotion/gesture frames through a semantic character component;
- preserves all blended emotion dimensions plus valence/arousal/dominance in the Blueprint frame;
- preserves resolved posture, attention-target and expression semantics instead of reducing every
  channel to the conversation phase;
- feeds the local player camera into Attention every 50 ms when no fresher external tracker target
  exists, so eye-first/head-follow Reflex remains alive without Backend Cognition;
- loads local gesture selection and soft montage mappings from a character DataAsset profile;
- applies profile-bound emotion, blink, viseme and jaw fallback to generic skeletal morph targets;
- asynchronously applies local gesture variants as body montages with runtime-epoch fencing;
- exposes profile-scaled eye/head angles, breathing, micro motion and weight shift through
  `UGahyeonCharacterAnimInstance` without auto-confirming visual latency;
- preserves backpressured durable work and reconnects without ACK on invalid durable payloads;
- exposes authoritative World/action/phase/emotion state as a Blueprint-readable frame snapshot;
- exposes counters to Blueprint for the first debug overlay.

MetaHuman and other licensed binary assets are not stored here. The initial map uses the Engine
entry map until the placeholder level is authored in an installed Editor. A native default GameMode
spawns the character shell with a follow camera and simple Engine basic-shape diagnostic body, so
the low-latency runtime can be inspected without licensed assets. Assigning any skeletal avatar to
the inherited Mesh automatically hides this diagnostic geometry.

`AGahyeonCharacterPawn` is the source-only replaceable shell for that level. It creates the
semantic presentation, World action and low-frequency debug components, owns an AI controller for
NavMesh requests, and keeps the inherited skeletal mesh as the replaceable MetaHuman boundary.
An installed Hero uses the fixed generated class
`/Game/GahyeonGenerated/Characters/Gahyeon.Gahyeon_C`; it must inherit this shell rather than a
plain MetaHuman/Character Blueprint. `UGahyeonHeroRuntimeSettings` soft-loads it at startup, falls
back to the diagnostic shell during source-only development, and can fail closed with
`bRequireHeroAsset=True` in an approved production build. The whole generated mount is always
cooked, while Core and network code retain no MetaHuman asset references.
Its default spring-arm camera is placed in front of the avatar. The camera position is transformed
from World space relative to a configurable local eye/head origin; an external camera/face tracker
holds priority for 750 ms after each valid observation, then the local camera resumes as fallback.
`UGahyeonRuntimeDebugComponent` can feed a UMG overlay through `BuildStatusText()`; optional direct
screen drawing is disabled by default and never participates in behavior decisions.

## Static validation

From the repository root:

```bash
./scripts/verify_unreal_stage_scaffold.sh
```

Backend Cognition and TTS run in separate bounded executors. Their thread/queue bounds and the
maximum streamed sentence size are deployment settings under `gahyeon.unreal.runtime`; defaults
are deliberately small so slow model or speech providers cannot block the WebSocket ingress path.

## Engine build gate

Install Unreal Engine 5.6 with C++ support, then generate project files and build the
`GahyeonStageEditor` Development target. This repository cannot claim VS-2 complete until the
Editor target has built and opened on a machine with UE 5.6 installed.

After installing the Engine, the authoritative local gate is:

```bash
GAHYEON_UE_ROOT="/path/to/UE_5.6" ./scripts/run_unreal_engine_gate.sh
```

It rejects any Engine version other than 5.6, builds `GahyeonStageEditor`, runs the `Gahyeon.*`
Automation suite headlessly, and stores build/test evidence under
`artifacts/unreal-engine-gate/`. `--check-only` validates the installation without compiling;
`--build-only` stops after the Editor target build. Static scaffold tests are not a substitute
for this gate.

Do not enable the Backend WebSocket in production yet. The basic socket/hello, SaveGame schema,
and bounded exponential reconnect/backoff with jitter exist. A transient host now activates the
HTTP PCM16 WAV loader, AudioComponent boundary, and interruption delegate without Blueprint
placement. The source now includes the visible diagnostic character, NavMesh/interaction callback
adapter, semantic AnimInstance bridge and on-device latency overlay; real MetaHuman/montage assets
and PIE evidence remain subsequent Vertical Slice work. A durable
event must not call `CompleteDurableEvent` until its isolated typed handler has succeeded.
## World action adapter

가시 Pawn에는 `UGahyeonCharacterPresentationComponent`와
`UGahyeonWorldActionComponent`를 추가한다. 후자는 semantic target을 NavMesh 이동으로
변환하고 실제 도착/interaction animation 종료만 Core에 보고한다. Backend나 LLM은
NavMesh 경로, montage asset, 프레임 단위 위치를 선택하지 않는다.
