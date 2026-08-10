# Gahyeon Core migration

This document records the incremental migration from a Discord bot to a
platform-independent Gahyeon agent. Existing Discord behavior remains supported
while each platform boundary is made explicit.

## Target dependency direction

```text
Discord / Headless / Desktop adapters
                 │
                 ▼
       ConversationUseCase
                 │
                 ▼
       ConversationAgentPort
                 │
                 ▼
LLM/runtime/provider infrastructure
```

Types under `com.gahyeonbot.core` must not import JDA, Spring Web, Piper,
OpenAI, or other provider-specific classes. STT and TTS will follow the same
port-and-adapter rule: Core owns capability contracts; infrastructure owns
provider implementations.

`CoreDependencyBoundaryTest` enforces this boundary. Discord bootstrap,
LavaPlayer/JDA audio, slash-command registration, and voice-channel scheduling
now live under `adapters.discord` rather than the Core namespace.

## Milestone 1: headless conversation seam

Implemented:

- internal `ActorId` rather than a Discord type in the Core contract;
- provider-neutral `MemoryUseCase`, `MemorySnapshot`, and role-preserving
  `MemoryMessage`; the JPA conversation history is now its persistence adapter;
- provider-neutral Tool risk and decision policy in Core. Unknown tools fail
  closed, write tools require approval, and destructive tools are denied;
  Spring AI annotations/callback execution remain provider infrastructure;
- typed `EmotionState` with intensity persisted alongside World State. Desktop
  restores both expression and intensity after a Core restart;
- deterministic conversation admission policy in Core. Message validation,
  normalized injection filtering, duplicate decisions, and actor/global limits
  are tested without a database or moderation provider; infrastructure only
  gathers usage and moderation facts;
- persisted `Principal` and `ExternalIdentity(provider, externalId)` records;
- `ConversationSessionId`, `ClientSource`, and `ConversationModality`;
- immutable `ConversationSession` client context;
- platform-neutral request/response and `ConversationUseCase`;
- outbound `ConversationAgentPort`;
- Discord text listener translated into the new Core request;
- the `/가현아` slash command now translates into the same Core request instead
  of invoking provider/admission infrastructure directly;
- an opt-in headless HTTP adapter;
- compatibility adapter that preserves the existing rate-limit, moderation,
  memory, and `AgentRuntime` behavior;
- tests proving Desktop/Headless requests do not require Discord context.
- Discord voice conversation now builds the same Core request; JDA capture and
  playback remain in the Discord service while conversation orchestration is no
  longer called with a Discord-shaped provider method.
- STT now enters through `TranscriptionUseCase` and `SpeechRecognitionPort` with
  a defensive binary `AudioInput`; the provider no longer defines the
  application-facing contract.
- TTS now enters through a logical `VoiceProfileId` and returns defensive
  binary `AudioOutput` segments. Piper/Voicebox/Edge selection and provider
  temporary files remain behind `TtsServiceSynthesisAdapter`; only the Discord
  adapter materializes a playback file for Lavaplayer.
- Conversation lifecycle emits schema-versioned persistent events with global
  sequence cursors. Headless/Desktop transports can resume reads through
  `GET /api/gahyeon/events?afterSequence=...` without owning Core state.
- Desktop requests resolve a stable installation ID through the platform
  identity map and enter Core with `ClientSource.DESKTOP`; clients never submit
  a database principal ID.
- Desktop clients can consume session-scoped Server-Sent Events and resume from
  a durable sequence cursor through
  `GET /api/gahyeon/desktop/events?sessionId=...&afterSequence=...`.
- The independent Electron/Vue Desktop shell now contains a Three.js stage,
  camera follow behavior, renderer-neutral semantic state reducer, diagnostic
  fallback character, and lazy-loaded VRM adapter. No AI provider runs in the
  renderer.
- Event schema v2 introduces explicit `SESSION`, `WORLD`, and `SYSTEM` scopes,
  so persistent world and presentation events no longer require a fabricated
  conversation session.
- World State is a separate persisted aggregate containing room, world
  coordinates, activity, activity start time, outfit, world time, emotion,
  interaction target, and an optimistic revision. Desktop restores the latest
  snapshot before consuming newer WORLD events.
- A deterministic FSM selects sleep, work, reading, looking outside, relaxing,
  and idle behavior from time and state. Bed, desk, bookshelf, chair, window,
  and room center are explicit interaction points. Conversation presence pauses
  autonomous transitions and restores the previous activity after the final
  concurrent conversation ends.
- Desktop microphone input is encoded as mono PCM WAV and enters the existing
  Core transcription use case. Core TTS returns binary segments to Desktop;
  speaker playback and amplitude-derived VRM visemes remain presentation
  responsibilities.
- The Three.js stage now renders bedroom, living-room, workspace, hallways, and
  interaction objects. Doorway waypoints produce constant-speed room traversal,
  and the camera follows the character's interpolated position.
- Looking Glass is an opt-in lazy WebXR display adapter over the same Three.js
  scene and `StageState`. Bridge/device absence does not affect the normal
  Desktop renderer; physical Go calibration remains a hardware acceptance test.
- VRM activities support official VRMA humanoid retargeting and 350 ms mixer
  cross-fades. A deterministic procedural pose set covers all activities when a
  licensed clip is absent, including presentation-selected walking while a
  waypoint path is active.
- Streaming utterance segmentation is platform-neutral. Pre-roll, speech onset,
  minimum speech, short-utterance silence, maximum duration, and flush decisions
  live in `StreamingUtteranceAccumulator`; Discord only normalizes JDA PCM and
  supplies the TEN VAD adapter.

The headless HTTP adapter is disabled by default. With no client token it only
accepts loopback traffic. Configure the same high-entropy token in Core and a
remote client before exposing the transport:

```bash
GAHYEON_HEADLESS_ENABLED=true GAHYEON_BEHAVIOR_ENABLED=true ./gradlew bootRun
```

```bash
GAHYEON_HEADLESS_ENABLED=true GAHYEON_CLIENT_TOKEN='<secret>' ./gradlew bootRun
```

LLM infrastructure is independently opt-in with
`GAHYEON_AGENT_PROVIDER=openai`. Without it, Core still boots and serves World,
Event, STT/TTS readiness, and Desktop presentation APIs; conversation reports
the provider as unavailable instead of preventing the process from starting.

With the application's `/api` context path, the endpoint is:

```text
POST /api/gahyeon/conversations/{sessionId}/messages
POST /api/gahyeon/desktop/conversations/{sessionId}/messages
GET  /api/gahyeon/desktop/worlds/{worldId}
POST /api/gahyeon/desktop/worlds/{worldId}/move
POST /api/gahyeon/desktop/worlds/{worldId}/activity
POST /api/gahyeon/desktop/worlds/{worldId}/emotion
GET  /api/gahyeon/desktop/speech/status
POST /api/gahyeon/desktop/speech/transcriptions
POST /api/gahyeon/desktop/speech/segments
POST /api/gahyeon/desktop/speech/synthesis
```

Example body:

```json
{
  "requestId": "local-test-1",
  "actorId": 1,
  "displayName": "local-user",
  "message": "안녕하세요"
}
```

## Known compatibility debt

- Discord identities now resolve through persisted external-identity records.
  During the compatibility phase, a Discord principal keeps the same numeric
  value as its prior user ID so existing memory and runtime ownership remain
  readable. Desktop account linking still needs an internal-ID allocator and a
  linking flow before this compatibility rule can be removed.
- Tool execution receives an optional platform-neutral `agent.toolScopeId`.
  The legacy runtime ledger still stores this value in its `guild_id` column;
  a schema migration is needed before that compatibility name disappears.
- `AgentGateway` currently describes response modality (`TEXT`, `VOICE`,
  `SYSTEM`) rather than client source. It should eventually be renamed or split
  without rewriting the runtime loop.
- Headless/Desktop expose request/response plus a versioned event stream.
  Bearer authentication protects remote transport; per-account credential
  linking is still required before multi-user remote access.

## Next migration slices

1. Replace the local Desktop installation identity with authenticated account
   linking before exposing the transport outside localhost.
2. Acquire and validate licensed Gahyeon-specific VRMA assets against the real
   character model; the loader, blending, and procedural fallback are complete.
3. Replace diagnostic room geometry with production assets and navmesh data.
4. Complete physical Looking Glass Go depth/performance acceptance.

Discord-only moderation, guild music, and DM delivery remain Discord adapter
capabilities unless explicitly exposed to Gahyeon as tools.
