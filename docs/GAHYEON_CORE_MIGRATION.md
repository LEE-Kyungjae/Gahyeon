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
- JDA voice receive, 48 kHz Discord PCM framing, Guild channel configuration,
  Lavaplayer playback, and Discord speech sanitization live under
  `adapters.discord`. The neutral `services.assistant` and `services.tts`
  packages are guarded against JDA/Lavaplayer dependencies in CI. Discord music,
  moderation, Guild configuration, and DM campaign delivery have also moved to
  adapter namespaces; the same guard now covers the complete `services` tree.
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
- Identity resolution is an Application port (`IdentityResolutionUseCase`). Its
  JPA entities, repositories, transaction boundary, legacy Discord-ID reuse,
  and local-ID allocation live in `JpaIdentityResolutionAdapter` rather than
  Application.
- Conversation memory and agent execution cross their runtime boundaries with
  an internal `ActorId`; generic tool authorization context is named
  `toolScopeId`. Agent session/run persistence uses `actor_id` and `tool_scope_id`;
  Discord-specific names no longer exist in the generic ledger schema.
- Agent run/session JPA properties, ownership checks, approval, cancellation,
  and resume APIs use `actorId`/`toolScopeId` semantics. The Discord `/agent`
  command resolves its external user through `DiscordIdentityMapper` before it
  can inspect or control a run. Flyway V30 and V32 migrate existing `guild_id` and
  `user_id` values losslessly to `tool_scope_id` and `actor_id`.
- Admission usage records use `requestId`, `actorId`, `actorDisplayName`, and
  `toolScopeId` throughout Java and persistence. Flyway V31 losslessly renames
  legacy `interaction_id`, `user_id`, `username`, and `guild_id` to
  `request_id`, `actor_id`, `actor_display_name`, and `tool_scope_id`.
- The provider-independent admission/cost ledger is `ModelUsage` backed by
  `model_usage`. Flyway V35 renames the legacy OpenAI-named table, request
  constraint, indexes, and identity sequence without rewriting usage data.
- Content safety is an Application port. `ConversationAdmissionService` consumes
  only `ContentSafetyPort.Decision`; OpenAI HTTP/auth/JSON remains inside
  `OpenAiContentSafetyAdapter`, and explicit `none` mode leaves the deterministic
  local admission policy active.
  The synchronous safety hop has bounded connect/read timeouts and outcome-tagged
  latency metrics so an unavailable provider cannot indefinitely stall Cognition.
  Its independent cooldown circuit skips repeated failed network calls and permits
  exactly one half-open recovery probe without changing the model-provider circuit.
- Conversation availability is owned by `AgentRuntime.isReady()`, not by checking
  an OpenAI/OpenRouter API-key string in admission. Disabled runtime reports false;
  local or differently authenticated runtime implementations can report their own
  readiness without changing Conversation code. Admission does not cache this state;
  runtime failure and recovery are observed on every new request without a restart.
- `DefaultAgentRuntime` classifies only model transport/inference failures as provider
  availability failures. A bounded cooldown prevents immediate retry storms, then admits
  exactly one atomic half-open recovery probe; concurrent probe candidates are rejected
  before model I/O. Tool and presentation-observer failures do not lower provider health.
- Conversation memory and agent display-name persistence now use `actor_id` and
  `actor_display_name` as well. Flyway V34 losslessly renames the legacy
  `conversation_history.user_id` and `agent_runs.username` columns and their
  actor-owned index names.
- Desktop, Headless, and Unreal client-local session IDs are source-namespaced
  before entering Core, preventing equal external strings from merging sessions
  across adapters. Replay readers temporarily accept legacy unprefixed events.
- Desktop clients can consume session-scoped Server-Sent Events and resume from
  a durable sequence cursor through
  `GET /api/gahyeon/desktop/events?sessionId=...&afterSequence=...`.
- Desktop conversation execution uses the Core streaming use case. Text deltas
  are delivered as non-durable `conversation.delta` SSE events immediately,
  while lifecycle/completion remains in the durable event log. A per-session
  generation token cancels an older cognition stream when a newer request is
  admitted, and the final HTTP response reconciles the transient text.
- The independent Electron/Vue Desktop shell now contains a Three.js stage,
  camera follow behavior, renderer-neutral semantic state reducer, diagnostic
  fallback character, and lazy-loaded VRM adapter. No AI provider runs in the
  renderer.
- The 3D Stage itself is an asynchronous presentation boundary. The initial
  conversation/transport bundle is about 91 kB minified; Three core, Stage,
  VRM, and WebXR are separate lazy chunks, so renderer parsing cannot delay the
  basic client shell.
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
- Desktop incrementally accumulates streamed text into safe sentence boundaries.
  Each completed sentence enters a single ordered TTS playback sequence before
  cognition finishes; the final HTTP response flushes only the suffix not yet
  observed over SSE. Cancellation invalidates the sequence so stale synthesis
  cannot resume avatar speech.
- Desktop supports presentation-level barge-in: a new text request immediately
  stops prior playback and supersedes the previous cognition generation, while
  microphone start also calls the explicit active-conversation cancellation
  endpoint before recording. Stale request failures are not surfaced as errors
  for the newer turn.
- Barge-in also aborts in-flight browser/Electron STT and TTS fetches. Electron
  owns a per-WebContents request registry, cancels every speech request for that
  renderer only, and clears all controllers during application shutdown.
- STT waiting is interruptible. Text submission or another microphone press
  advances a local transcription generation, aborts the older transport, and
  prevents its transcript/error callback from mutating the new turn.
- Microphone lifecycle drives the local Reflex layer immediately: recording
  start selects a dedicated `listening` pose, recording end selects `thinking`
  during STT, and capture failure returns to idle. These transitions do not
  wait for an LLM or a durable Core event.
- The Desktop recorder now feeds 2,048-sample PCM frames into the local
  hysteresis VAD (60 ms attack, 450 ms release). A detected speech end submits
  the utterance automatically, retains 250 ms pre-roll, and keeps manual stop
  plus the 20-second hard cap as safety fallbacks.
- STT provider connect/read defaults are bounded to 8 seconds. Browser and
  Electron speech transports additionally bound transcription to 10 seconds
  and each TTS sentence to 25 seconds, returning through the normal localized
  failure path instead of leaving the avatar in Thinking indefinitely.
- Desktop keeps bounded 256-sample latency rings for VAD→Listening state,
  VAD-end→STT-final, request→first delta, request→first actual audio start, and
  barge-in request→audio-source ended.
  `window.gahyeonRuntimeDiagnostics.latencySnapshot()` exposes bounded sample
  count, cumulative total, p50/p95/p99/max, and acceptance-budget violations
  without retaining transcript or audio content.
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
- Desktop presentation strings are separated into Korean and English locale
  resources. The operating-system language is detected on first launch and a
  persisted selector can override it without changing Core state.
- AIRI was reviewed as a presentation architecture reference. Gahyeon adopts
  the stage/client separation and web rendering approach independently, while
  explicitly excluding AIRI's duplicate LLM, memory, speech-provider, and
  database stacks; see `docs/AIRI_DESKTOP_ANALYSIS.md`.

`scripts/verify_core_platform_boundaries.py` enforces this direction in CI.
Core may depend only inward on Core/JDK types; Application cannot import
adapters, commands, entities, listeners, repositories, services, or a Discord
SDK. Fully qualified platform references are checked as well as imports.

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
When Headless or Unreal transport is enabled, shared `AgentRuntimeReadiness`
makes that unavailable runtime fail `/api/health` and the Actuator
`agentRuntime` component. Discord-only deployments keep conversation optional.

With the application's `/api` context path, the endpoint is:

```text
POST /api/gahyeon/conversations/{sessionId}/messages
POST /api/gahyeon/desktop/conversations/{sessionId}/messages
DELETE /api/gahyeon/desktop/conversations/{sessionId}/active
GET  /api/gahyeon/desktop/worlds/{worldId}
POST /api/gahyeon/desktop/worlds/{worldId}/move
POST /api/gahyeon/desktop/worlds/{worldId}/activity
POST /api/gahyeon/desktop/worlds/{worldId}/emotion
POST /api/gahyeon/desktop/worlds/{worldId}/actions/{actionId}/complete
GET  /api/gahyeon/desktop/speech/status
POST /api/gahyeon/desktop/speech/transcriptions
POST /api/gahyeon/desktop/speech/segments
POST /api/gahyeon/desktop/speech/synthesis
```

Headless example body:

```json
{
  "requestId": "headless-test-1",
  "externalActorId": "client-account-42",
  "displayName": "local-user",
  "message": "안녕하세요"
}
```

`externalActorId` is scoped to `IdentityProvider.HEADLESS`; clients never send
or select Gahyeon's internal numeric `ActorId`.
Legacy request bodies containing numeric `actorId` remain accepted during the
migration, but the value is resolved as a `HEADLESS` external key rather than
being trusted as an internal principal ID.

Desktop example body:

```json
{
  "requestId": "local-test-1",
  "installationId": "local-installation-id",
  "displayName": "local-user",
  "message": "안녕하세요"
}
```

## Known compatibility debt

- Discord identities now resolve through persisted external-identity records.
  During the compatibility phase, a Discord principal keeps the same numeric
  value as its prior user ID so existing memory and runtime ownership remain
  readable. New non-Discord identities have an internal-ID allocator and Desktop
  linking can merge their actor-owned records into the authenticated Discord
  principal. Removing the preferred legacy numeric ID remains a separate data
  migration after all deployed records use external identity mappings.
- Tool execution receives an optional platform-neutral `agent.toolScopeId`, and
  the agent runtime ledger stores it in `tool_scope_id`. Discord guild IDs are
  adapter inputs only and are no longer named in the generic agent schema.
- Agent response modality is represented explicitly by `AgentModality`
  (`TEXT`, `VOICE`, `SYSTEM`) and persisted in the neutral `modality` column;
  client source remains a separate session concern.
- Headless/Desktop expose request/response plus a versioned event stream.
  Bearer authentication protects deployment-level remote transport. Desktop can consume a
  128-bit, ten-minute, single-use code issued by the authenticated Discord actor
  to bind an installation to the same internal Principal. Successful linking rotates
  a 256-bit installation credential stored only as a server-side hash; Electron keeps
  the raw value in OS safeStorage. Desktop endpoints derive the authenticated Principal
  from it and verify ownership of the claimed installation. Headless and Unreal do not
  accept Desktop account credentials.
- Desktop live session IDs are atomically claimed by one installation. SSE attach and
  cancellation carry the same installation ID and cross-installation reuse is rejected.
  This prevents accidental/hostile live-session interference under the current shared
  client credential, but it is not proof of account ownership and does not replace the
  authenticated linking flow above.

## Next migration slices

1. Add optional automatic rotation with a separate refresh credential or require deliberate
   Discord re-authorization. Desktop credentials expire after 90 days, Discord lists the exact
   expiry, Desktop warns seven days beforehand, and expired/revoked installations recover with
   a new one-time code.
2. Produce and approve the single Gahyeon AAA hero master, then derive the Hero
   Desktop, performance, Looking Glass, and VRM compatibility packages. The
   existing VRM loader remains a diagnostic and compatibility renderer rather
   than the master quality target; see `AAA_CHARACTER_PIPELINE.md`.
3. Replace diagnostic room geometry with production assets and navmesh data.
4. Complete physical Looking Glass Go depth/performance acceptance.

Discord-only moderation, guild music, and DM delivery remain Discord adapter
capabilities unless explicitly exposed to Gahyeon as tools.
