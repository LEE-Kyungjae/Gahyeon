# Gahyeon

[한국어](README.md) · [English](README.en.md) · [日本語](README.ja.md)

Gahyeon is a modular embodied AI agent with persistent memory, voice, autonomous
behavior, and a persistent world. It is not a screen attached to a Discord bot:
independent clients and adapters share one Gahyeon Core.

> The priority is a low-latency real-time AI character architecture, not a graphics demo.
> Reflex, Behavior, and Cognition must keep running independently while the LLM responds.

```text
                         Gahyeon Core
 Conversation · Memory · STT/TTS · Tools · Session
        Emotion · Behavior · Persistent World
                              │
                   Event · HTTP · WebSocket
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
 Discord Adapter     Desktop Compatibility    Unreal Stage
                      Three.js / VRM          AAA target
                                                  │
                                      Monitor · Looking Glass
```

Core decides what Gahyeon says, remembers, feels, does, and where she moves.
Presentation expresses those decisions through voice, facial animation, lip sync,
body animation, and rendering.

## Current status

| Area | Current evidence |
|---|---|
| Core/Application | Platform-neutral Conversation, Session, Speech ports, Event, and World/Behavior boundaries |
| Headless | APIs and persistent World run without Discord or an LLM provider |
| Discord Adapter | Existing slash commands, text/voice conversation, music, and operations remain available |
| Desktop compatibility client | Electron/Vue/Three.js text, microphone, audio, VRM, and World path implemented |
| Unreal Backend Adapter | Conditional WebSocket v1, replay/cursor/snapshot, and streaming speech implemented |
| Unreal RuntimeCore | Engine-neutral C++20 Reflex/Behavior/Cognition, VAD, speech, viseme, World, persistence, and reconnect harnesses |
| Unreal Stage | UE 5.6 source project, native runtime/ingress, and asset-free diagnostic pawn/camera implemented; Editor build, MetaHuman, NavMesh, and packaged build remain unverified |
| Looking Glass | Desktop WebXR path exists; acceptance on a physical Go display is pending |
| Voice production | A deduplicated 5,000-sentence Voicebox teacher corpus is being generated; completion automatically hands off to acoustic/STT/speaker QC, staged Piper training, and blind review |
| Character production | SDXL LoRA training/comparison is complete; user originals are locked as canonical identity in the G0 pack with a G1 modeling handoff/drafts, while the final hero mesh remains incomplete |

Reference-runtime evidence is not presented as packaged-Unreal acceptance. See the
[acceptance evidence matrix](docs/unreal/ACCEPTANCE_STATUS.md) for RT-01 through RT-13,
including slow-secondary-renderer isolation and the remaining hardware tests.

## Design principles

- Discord, Desktop, and Unreal are clients/adapters of Core.
- Core domain does not depend on JDA, Electron, Unreal, Spring Web, or provider types.
- The LLM emits semantic intent, not frame transforms or animation asset IDs.
- Reflex, Behavior, and Cognition run concurrently on different timescales.
- Headless Behavior and World continue when no renderer is connected.
- Network callbacks never mutate Game Thread state directly.
- Durable cursors and action commands are acknowledged/sent only after persistence succeeds.
- Memory owns what Gahyeon remembers; World State owns where she is and what she is doing.

## Requirements

- Java 21
- Node.js 20 or later and npm
- Production: PostgreSQL 16 recommended
- Development default: in-memory H2 in PostgreSQL compatibility mode
- Unreal development: Unreal Engine 5.6 and compatible MetaHuman plugins

## Quick start

### 1. Verify the workspace

```bash
./gradlew test
python3 scripts/verify_core_platform_boundaries.py
./scripts/test_unreal_runtime_core.sh
./scripts/verify_unreal_stage_scaffold.sh
./scripts/verify_unreal_protocol_contract.sh
./scripts/test_run_unreal_engine_gate.sh
./scripts/test_smoke_headless_core.sh

cd desktop
npm ci
npm test
npm run build
```

### 2. Run Headless Core

Headless Core runs independently without Discord, Spotify, or OpenAI credentials.
Configure credentials only when enabling the adapter or provider that consumes them.

```bash
BOT_ENABLED=false \
WEATHER_PREFETCH_ENABLED=false \
GAHYEON_HEADLESS_ENABLED=true \
GAHYEON_BEHAVIOR_ENABLED=true \
TTS_ENABLED=false \
./gradlew bootRun
```

Run `./scripts/smoke_headless_core.sh` to reproduce a credential-free, Discord-disabled boot plus
health and World-revision HTTP smoke checks. Conversation readiness is expected to be `DOWN`
in this credential-free smoke while DB and World behavior are verified.
Use `GAHYEON_HEADLESS_SMOKE_MODE=jar ./scripts/smoke_headless_core.sh` to verify the
packaged release JAR instead of Gradle `bootRun`.
On a slow development machine, set `GAHYEON_HEADLESS_SMOKE_STARTUP_TIMEOUT` within 30–900 seconds.
Run `./scripts/smoke_headless_container.sh` to verify the actual Docker-image boundary.
It disables Discord in a temporary container, checks health and a World revision update,
then removes only the container it created.

The default API root is `http://127.0.0.1:8080/api`. Without a client token, client APIs
accept loopback traffic only.

### 3. Run the Desktop development client

In another terminal:

```bash
cd desktop
npm ci
GAHYEON_CORE_API_URL=http://127.0.0.1:8080/api npm run dev
```

For a remote Core, configure the same high-entropy `GAHYEON_CLIENT_TOKEN` on both sides.
See [`desktop/.env.example`](desktop/.env.example) for VRM/VRMA and environment assets.

### 4. Enable LLM conversation

```bash
GAHYEON_AGENT_PROVIDER=openai \
AGENT_API_KEY='<key>' \
AGENT_BASE_URL='https://openrouter.ai/api' \
AGENT_MODEL='<model>' \
GAHYEON_HEADLESS_ENABLED=true \
BOT_ENABLED=false \
./gradlew bootRun
```

Do not enable `GAHYEON_AGENT_TOOL_SAFE_STREAMING_ENABLED` until the chosen provider/model
has been verified to keep tool calls separate from speakable text.

## Discord compatibility adapter

```bash
BOT_ENABLED=true \
TOKEN='<discord-token>' \
APPLICATION_ID='<application-id>' \
GAHYEON_AGENT_PROVIDER=openai \
AGENT_API_KEY='<key>' \
./gradlew bootRun
```

The existing `/설정`, `/가현아`, leave, music, and operations slash-command paths remain.
Voice conversation uses `TEN VAD → STT → Conversation → TTS`; Conversation and Speech
domain code does not reference Discord objects.

With `BOT_ENABLED=true`, a missing/rejected Discord token or initialization failure keeps the
application process alive but makes `/api/health` and the Actuator Discord health fail closed as
`FAILED`/DOWN. Only a Blue/Green follower legitimately waiting for the PostgreSQL advisory lock is
`STANDBY`/UP; `BOT_ENABLED=false` is the explicit `DISABLED`/UP state.

## Unreal Stage

The Backend WebSocket adapter and C++20 RuntimeCore are ready but disabled by default.
They must not be enabled in production before UE 5.6 Editor and packaged-build validation.

Authoritative gate on a development machine with UE 5.6 installed:

```bash
GAHYEON_UE_ROOT="/path/to/UE_5.6" ./scripts/run_unreal_engine_gate.sh
```

On the GTX 1660 Ti Windows authoring machine, validate the canonical Stage first:

```powershell
.\scripts\run_unreal_engine_gate.ps1 -UnrealRoot "C:\Program Files\Epic Games\UE_5.6"
```

After the Editor gate, add `-Package` to build and seal the packaged Development output.
Run, aggregate, and verify the packaged ten-minute measurement with:

```powershell
.\scripts\run_desktop_realtime_acceptance.ps1 `
  -PackagedRoot "C:\gahyeon-package" `
  -EvidenceRoot "C:\gahyeon-evidence\desktop-0001"
```

- [Unreal architecture](docs/unreal/ARCHITECTURE.md)
- [Protocol v1](docs/unreal/PROTOCOL_V1.md)
- [Adapter integration contract](docs/unreal/ADAPTER_INTEGRATION.md)
- [Vertical Slice sequence](docs/unreal/VERTICAL_SLICE.md)
- [Real-time acceptance](docs/unreal/REALTIME_ACCEPTANCE.md)
- [Development readiness](docs/unreal/READINESS.md)

## Essential configuration

| Variable | Purpose | Default |
|---|---|---|
| `BOT_ENABLED` | Connect the Discord Adapter | `true` |
| `GAHYEON_HEADLESS_ENABLED` | Enable Headless/Desktop APIs | `false` |
| `GAHYEON_CLIENT_TOKEN` | Remote-client bearer authentication | none; loopback only |
| `GAHYEON_BEHAVIOR_ENABLED` | Enable the Core behavior scheduler | `false` |
| `GAHYEON_UNREAL_WEBSOCKET_ENABLED` | Enable the Unreal WebSocket endpoint | `false` |
| `GAHYEON_UNREAL_COGNITION_*` | Bound Unreal Cognition workers and queue | small bounded pool |
| `GAHYEON_UNREAL_TTS_*` | Bound Unreal TTS workers and queue | small bounded pool |
| `GAHYEON_UNREAL_VISEME_ALIGNER_*` | Exact lip-sync HTTP aligner, 250 ms playback deadline, and dedicated bounded pool | disabled |
| `GAHYEON_UNREAL_SPEECH_SEGMENT_MAX_CHARACTERS` | Maximum streamed TTS sentence segment | `120` |
| `GAHYEON_AGENT_PROVIDER` | Select the Spring AI chat provider | `none` |
| `GAHYEON_AGENT_PROVIDER_FAILURE_COOLDOWN_MILLIS` | Delay before a model-provider recovery probe | `5000` |
| `GAHYEON_CONTENT_SAFETY_PROVIDER` | Pluggable input-safety adapter (`openai` or `none`) | `openai` |
| `GAHYEON_CONTENT_SAFETY_CONNECT_TIMEOUT_MILLIS` / `READ_TIMEOUT_MILLIS` | Input-safety provider bounds (100–5000 ms each) | `300` / `700` |
| `GAHYEON_CONTENT_SAFETY_FAILURE_COOLDOWN_MILLIS` | Delay before one input-safety recovery probe | `30000` |
| `GAHYEON_AGENT_TOOL_SAFE_STREAMING_ENABLED` | Token streaming for a verified provider | `false` |
| `GAHYEON_AGENT_STREAMING_VERIFIED_BASE_URL` | Exact provider base URL that passed the streaming probe | none |
| `GAHYEON_AGENT_STREAMING_VERIFIED_MODEL` | Exact model ID that passed the streaming probe | none |
| `AGENT_API_KEY`, `AGENT_BASE_URL`, `AGENT_MODEL` | LLM endpoint | provider-specific |
| `ASSISTANT_STT_*`, `ASSISTANT_VAD_*` | Discord speech recognition and VAD | environment-specific |
| `TTS_PROVIDER` | `voicebox`, `edge`, or `custom` | `voicebox` |

See [Custom Voice TTS](docs/CUSTOM_VOICE_TTS.md) for provider and fallback settings.

## Repository layout

```text
src/main/java/com/gahyeonbot/
├─ core/          framework/platform-neutral domain
├─ application/   use cases, ports, orchestration
└─ adapters/      Discord, Desktop, Headless, Unreal, and provider implementations

desktop/           Electron/Vue/Three.js compatibility presentation client
unreal/RuntimeCore/ engine-neutral C++20 real-time reference runtime
unreal/GahyeonStage/ UE 5.6 source-only Stage project and native module
docs/unreal/        Unreal architecture, protocol, acceptance, and integration contracts
scripts/            Voice/Piper, SDXL asset pipeline, and operational tools
```

## Naming and compatibility

The official product, character, and architecture name is **Gahyeon**. The
`com.gahyeonbot` Java package, existing database/container names, GHCR path, and a few
service-file identifiers retain `gahyeonbot` as legacy operational identifiers. They do
not represent the current product name and must not be bulk-renamed before a coordinated
repository and deployment migration.

## Documentation

- [System architecture](docs/ARCHITECTURE.md)
- [Core extraction record](docs/GAHYEON_CORE_MIGRATION.md)
- [API](docs/API.md)
- [Desktop](desktop/README.md)
- [AIRI analysis](docs/AIRI_DESKTOP_ANALYSIS.md)
- [AAA Character Pipeline](docs/AAA_CHARACTER_PIPELINE.md)
- [Character quality gates](docs/GAHYEON_CHARACTER_QUALITY_GATES.md)
- [G1 Modeling Handoff](docs/GAHYEON_G1_MODELING_HANDOFF.md)
- [Unreal acceptance status](docs/unreal/ACCEPTANCE_STATUS.md)
- [Looking Glass](docs/LOOKING_GLASS.md)
- [Voice](docs/CUSTOM_VOICE_TTS.md)
- [Deployment](docs/DEPLOYMENT.md)

## Security and assets

Do not commit secrets, source voice recordings, training checkpoints, or licensed
VRM/VRMA/MetaHuman/environment assets to Git or container images. Use deployment secrets
and separate artifact storage.

SDXL outputs and generated drafts are not canonical facial evidence. Character identity authority
is the checksum-bound pack of user originals; inferred regions and approval state of generated G1
sheets remain recorded in a separate manifest.
