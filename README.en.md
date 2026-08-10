# Gahyeon

[한국어](README.md) · [English](README.en.md)

Gahyeon is a modular embodied AI agent with persistent memory, voice,
autonomous behavior, and a living 3D world. It is not a Desktop UI attached to
a Discord bot. One Gahyeon Core makes decisions; Discord and Desktop are
independent input/output adapters.

```text
                       Gahyeon Core
 Conversation · Memory · STT/TTS · Tools · Session
          Emotion · Behavior · Persistent World
                             │
                       Event / HTTP API
                  ┌──────────┴──────────┐
                  ▼                     ▼
          Discord Adapter        Desktop Client
                                      │
                            Desktop / Looking Glass
```

## Implementation status

| Area | Status |
| --- | --- |
| Headless Core | Boots without Discord or an LLM provider |
| Discord | Text, slash, voice, music, and operations remain compatibility adapters |
| Desktop | Korean/English UI, text, microphone, speaker, VRM, expression, lip sync, and world movement |
| Behavior/world | Deterministic FSM, interaction points, persistent location, room, activity, and emotion |
| Rendering | Monitor-first; optional Looking Glass WebXR renderer consumes the same World State |
| Speech | Replaceable STT and Voicebox/Edge/custom TTS; Piper distillation research tools |

Licensed character VRM/VRMA files and production environment assets must be
provided separately. Procedural character and world fallbacks keep the full
flow runnable without those assets.

## Quick start: Core + Desktop

Java 21+ and Node.js 20+ are required.

```bash
./gradlew clean test
GAHYEON_HEADLESS_ENABLED=true \
GAHYEON_BEHAVIOR_ENABLED=true \
BOT_ENABLED=false \
./gradlew bootRun
```

In another terminal:

```bash
cd desktop
npm install
npm test
npm run dev
```

For remote access, configure the same high-entropy
`GAHYEON_CLIENT_TOKEN` in Core and Desktop. Without a token, Gahyeon client APIs
accept loopback traffic only.

Enable LLM conversation with an OpenAI-compatible endpoint:

```bash
GAHYEON_AGENT_PROVIDER=openai \
AGENT_API_KEY='<key>' \
AGENT_BASE_URL='https://openrouter.ai/api' \
AGENT_MODEL='<model>' \
GAHYEON_HEADLESS_ENABLED=true BOT_ENABLED=false ./gradlew bootRun
```

World, events, Desktop, and speech-readiness APIs still boot when no provider is
configured.

## Discord adapter

```bash
BOT_ENABLED=true \
TOKEN='<discord-token>' \
APPLICATION_ID='<application-id>' \
GAHYEON_AGENT_PROVIDER=openai \
AGENT_API_KEY='<key>' \
./gradlew bootRun
```

`/setup` configures the legacy-compatible text and voice channels. `/gahyeona`
and dedicated-channel messages call the same `ConversationUseCase`; voice uses
the `TEN VAD → STT → Conversation → TTS` pipeline.

## Desktop distribution

```bash
cd desktop
npm run package   # unpacked app for the current OS
npm run dist      # distributable artifacts
```

Artifacts are written to `desktop/release/`. Signing and notarization
credentials belong in the release environment, never in the repository.

## Essential configuration

| Variable | Purpose | Default |
| --- | --- | --- |
| `BOT_ENABLED` | Discord connection | `true` |
| `GAHYEON_HEADLESS_ENABLED` | Headless/Desktop HTTP adapters | `false` |
| `GAHYEON_CLIENT_TOKEN` | Remote-client bearer authentication | none; loopback only |
| `GAHYEON_BEHAVIOR_ENABLED` | Autonomous behavior coordinator | `false` |
| `GAHYEON_AGENT_PROVIDER` | OpenAI-compatible agent adapter | `none` |
| `AGENT_API_KEY`, `AGENT_BASE_URL`, `AGENT_MODEL` | LLM provider | provider-specific |
| `WEATHER_PREFETCH_ENABLED` | Weather warmup and refresh | follows `BOT_ENABLED` |
| `ASSISTANT_STT_*`, `ASSISTANT_VAD_*` | Recognition and turn detection | environment-specific |
| `TTS_PROVIDER` | `voicebox`, `edge`, or `custom` | see configuration |

See [Custom Voice TTS](docs/CUSTOM_VOICE_TTS.md) for speech configuration.
Never commit secrets, source recordings, or model files.

## Repository boundaries

- `src/main/java/com/gahyeonbot/core`: framework/platform-neutral types and policies
- `src/main/java/com/gahyeonbot/application`: use cases and orchestration
- `src/main/java/com/gahyeonbot/adapters`: Discord, Desktop, Headless, and provider boundaries
- `desktop/electron`: native lifecycle, authenticated transport, narrow preload bridge
- `desktop/src/stage`: renderer-neutral state and Three/VRM presentation
- `desktop/src/audio`: recording, playback, presentation-only lip sync

Core decides dialogue, memory, emotion, behavior, and movement. Presentation
expresses those decisions. A dependency test prevents JDA, Spring Web, and
provider imports from leaking into Core.

## Verification

```bash
./gradlew clean test
cd desktop && npm test && npm run build
```

## Documentation

- [Core migration and status](docs/GAHYEON_CORE_MIGRATION.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Desktop](desktop/README.md)
- [AIRI analysis](docs/AIRI_DESKTOP_ANALYSIS.md)
- [VRM animation](docs/VRM_ANIMATION.md)
- [Looking Glass](docs/LOOKING_GLASS.md)
- [API](docs/API.md)
- [Deployment](docs/DEPLOYMENT.md)

Licensed under the [MIT License](LICENSE).
