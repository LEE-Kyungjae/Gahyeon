# Gahyeon Desktop

Desktop is a presentation client for Gahyeon Core. It does not own LLM,
memory, STT, TTS, behavior, or world-state decisions.

The interface detects Korean, English, or Japanese from the operating-system locale on
first launch. Users can switch languages from the conversation header; the
choice is persisted locally as `gahyeon.locale`.

The conversation shell and transport load independently from the 3D stage.
Three.js, Stage, VRM, and optional WebXR code are emitted as lazy chunks, so
basic interaction does not wait for the rendering stack to download or parse.

## Run locally

Start Core with the local-only transport enabled:

```bash
GAHYEON_HEADLESS_ENABLED=true GAHYEON_BEHAVIOR_ENABLED=true ./gradlew bootRun
```

Then start Desktop:

```bash
cd desktop
npm install
npm run dev
```

Build an unpacked native application for the current platform with
`npm run package`, or create distributable artifacts with `npm run dist`.
Release output is written to `desktop/release/`. Platform signing and
notarization credentials are intentionally supplied by the release environment;
they are not stored in this repository.

Set `GAHYEON_CORE_API_URL` when Core is not available at
`http://127.0.0.1:8080/api`.

Set the same high-entropy `GAHYEON_CLIENT_TOKEN` in the Core and Desktop process
environments for non-loopback access. Without a token, Core accepts Gahyeon
client APIs from loopback only. The token stays in Electron's main process and
is not exposed through the renderer preload API.

To load the currently implemented compatibility renderer, copy `.env.example`
to `.env` and set
`VITE_GAHYEON_VRM_URL` to a renderer-accessible URL. Without a model, the stage
uses a lightweight diagnostic character so world movement and camera behavior
remain testable.

Production compatibility packages use `VITE_GAHYEON_HERO_MANIFEST_URL` instead.
Desktop accepts only a Hero manifest v2 with `status=approved`, `gate=G5`, complete
G1–G5 evidence and a `three-vrm` package. It downloads that package with a 512 MiB
ceiling and verifies the exact byte count and SHA-256 before giving it to the VRM
renderer. If a production manifest is configured but fails validation, Desktop keeps
the diagnostic character and does not silently fall back to `VITE_GAHYEON_VRM_URL`.
The Desktop package format is one self-contained `.vrm`; it does not consume the
Unreal `unreal-content-zip`, whose dependency inventory and mount layout are verified
separately before an Engine build.

VRM is not the Gahyeon hero-master format. The production target is a single
AAA hero character with renderer-specific LOD packages behind the same semantic
presentation boundary. See `docs/AAA_CHARACTER_PIPELINE.md` and the hero asset
manifest contract under `docs/contracts/`.

Set `VITE_GAHYEON_WORLD_URL` to load a licensed GLB/GLTF environment. The asset
is presentation-only: Core coordinates, rooms, activities, and interaction
targets remain authoritative. Loading failure preserves the procedural home.

Optional activity clips are configured with `VITE_GAHYEON_VRMA_MANIFEST`.
Desktop retargets VRMA clips through the official Pixiv package and cross-fades
activity changes. Every activity has a procedural fallback, so a missing clip
does not stop the avatar. See `docs/VRM_ANIMATION.md` for the manifest and asset
acceptance rules.

Desktop queries Core speech readiness at startup. When configured, the mic
button records mono PCM WAV locally, sends it through Core STT, and submits the
transcript as a normal conversation. Replies are segmented through Core TTS and
played through the system speaker. Playback amplitude drives the renderer's
`aa` viseme; no microphone or audio-provider object enters Gahyeon Core.

As soon as streamed text reaches a sentence boundary, Desktop queues that
sentence through Core TTS and starts ordered playback without waiting for the
entire cognition response. The final response flushes only an unseen suffix;
starting a newer request cancels the older audio generation.
An unterminated LLM delta is force-flushed every 180 UTF-16 code units, without
splitting a surrogate pair, so missing punctuation or whitespace cannot defer
speech indefinitely or grow the renderer accumulator without a hard bound.
Within a segmented TTS response, playback keeps exactly one decoded segment of
look-ahead. The next segment can synthesize while the current source is audible,
but later segments remain queued, avoiding sentence gaps without building an
unbounded audio buffer. The same one-slot look-ahead crosses separately arriving
LLM sentences when the current sentence is on its final segment; the pending text
queue has a hard 64-item ceiling. Barge-in generation fencing discards prefetched audio.
Crossing that ceiling rejects the overflowing `enqueue` immediately instead of reporting
false admission behind an older playback promise; `finish` also retains the same failure as
the sequence-level fail-closed result. Fire-and-forget stream listeners attach an internal
rejection observer, so the immediate signal does not become an unhandled renderer rejection.
The player owns the active transport cancellation handle as part of the same lifecycle:
`stop`, sequence cancellation, replacement, and disposal abort pending segment/synthesis
requests as well as stopping the Web Audio source.
Browser and Electron also stream each synthesized response through a hard 16 MiB
limit. Both an oversized `Content-Length` and a chunked body that crosses the
limit are rejected before the payload can become an unbounded renderer allocation.

Text input remains available while a turn is running. Submitting a newer turn
immediately stops old playback and supersedes the Core generation. Starting the
microphone does the same through the active-conversation cancellation endpoint,
so the character can be interrupted instead of forcing the user to wait.
Browser and Electron transports also abort outstanding STT/TTS fetches. Electron
tracks requests per renderer, so cancelling one window cannot abort another
window's speech traffic; late downloads never return to decode/playback.
The microphone and text composer remain usable while STT is pending. A new
input increments the local transcription generation, aborts the old request,
and silently rejects its late transcript or cancellation error.
Microphone start also enters a local `listening` pose immediately; recording
end enters `thinking` while STT runs. These Reflex transitions remain responsive
even when cognition is unavailable.
Local RMS VAD automatically ends capture after 450 ms of post-speech silence,
with a 60 ms attack window and 250 ms retained pre-roll. Manual stop and the
20-second maximum remain available when VAD cannot establish an utterance.
Desktop also applies a 10-second STT transport timeout and a 25-second timeout
per synthesized sentence. Timeout returns the local state from Thinking or
Speaking through the same recoverable presentation error path.

Runtime latency evidence is kept in bounded 256-sample rings. From the Desktop
developer console, call:

```js
window.gahyeonRuntimeDiagnostics.latencySnapshot()
```

The snapshot reports ring sample count, cumulative total count, p50, p95, p99,
and max for VAD→Listening state, VAD-end→STT-final, request→first LLM delta,
request→first audible playback, and barge-in→audio-source ended. The bounded
metrics expose their applicable 100 ms, 3,000 ms, and 150 ms budgets plus
cumulative violation counts. Invalid durations are
rejected and samples do not persist user text or audio.

Conversation text begins updating from ephemeral `conversation.delta` events
instead of waiting for the final LLM response. Durable lifecycle events still
carry the resumable sequence cursor, while the HTTP completion response
reconciles the transient message. A newer request invalidates the older
per-session generation so stale deltas cannot continue driving presentation.

The diagnostic world contains bedroom, living-room, workspace, connecting
hallways, and the interaction objects used by the deterministic behavior
policy. Movement follows doorway waypoints while the camera follows the actual
character position rather than the final destination.
Core-owned `world.transition.target` events start Desktop navigation before the
authoritative revision commits. On arrival Desktop reports the exact action ID,
expected revision, and final position; Core validates and commits it. A lost
renderer acknowledgement falls back to the bounded Headless executor, while
action-result and snapshot events reconcile the local pending target. Listening,
Thinking, and Conversation reflexes pause autonomous travel immediately instead
of waiting for cognition or the action round trip.

Looking Glass support is opt-in through `VITE_GAHYEON_LOOKING_GLASS=true` and
requires Looking Glass Bridge. The adapter is lazy-loaded and renders the same
Three.js scene through WebXR; see `docs/LOOKING_GLASS.md` for setup and hardware
verification status.

## Boundaries

Durable SSE events are admitted only when their integer sequence is strictly newer than the
persisted cursor. Electron filters replay before IPC and the renderer repeats the check before
state, conversation text, or TTS side effects. Events without an ID remain ephemeral and do not
advance the durable cursor. The renderer persists a new cursor only after all synchronous event
side effects succeed, so a failed apply remains replayable after reconnect or restart.
Electron treats both transport errors and clean-but-unexpected SSE EOF as reconnect failures. Retry
delay grows from 250 ms to a bounded 5 s and resets after any delivered event; unsubscribe aborts a
pending delay immediately, preventing both tight reconnect loops and slow shutdown.
The streaming parser caps one incomplete event block at 65,536 characters. An oversized or
delimiter-free block fails only that connection and enters the same bounded reconnect path; a large
network chunk containing many individually bounded events remains valid.
CRLF normalization retains a trailing carriage return until the next network chunk arrives, so a
line ending split at the byte boundary cannot create a false blank line or hide a valid event.
Durable `conversation.completed`, `conversation.failed`, and `conversation.cancelled` events use
their Core `correlationId` to close replayed orphan streams after a Desktop restart. A still-active
local POST remains its own speech/error owner, preventing the SSE terminal from creating a duplicate
final message.
The rendered chat timeline is capped at 500 entries (roughly 250 recent turns), preferring the
welcome item and locally-owned pending responses. The hard cap still wins under malformed external
traffic. This bounds Desktop DOM/heap usage only; Core conversation history and persistent Memory
remain authoritative and are not truncated.
Completed request IDs used for HTTP/SSE deduplication live in a 30-second TTL registry capped at
2,048 identities. Expiration is lazy and deterministic, so a burst does not allocate thousands of
browser timers and malformed terminal traffic cannot grow the deduplication set without bound.
Electron advances valid durable cursors but forwards only the explicit Desktop presentation event
allowlist across IPC; a contract test keeps it identical to the browser bridge list. Malformed
UTF-8 fails the individual SSE connection instead of introducing replacement characters into state
or conversation text.
If an SSE outage outlives the short request-ID deduplication TTL, a replayed completion also checks
the authoritative Core `runId` already present in the timeline. Thus an HTTP result cannot be
materialized twice merely because terminal delivery was delayed.
Conversation POSTs have a 10-second hard transport deadline in both Electron and the browser
bridge. Timeout aborts the in-flight request, triggers best-effort Core generation cancellation,
and drives local presentation through `conversation.failed` back to Idle. Electron window teardown
also aborts all remaining conversation requests.
When the POST succeeds while SSE is unavailable, its HTTP response acts as a local semantic
completion fallback. It returns Attention/Thinking to Idle (or leaves active audio in Speaking)
only if that request still owns presentation, so a superseded late response cannot overwrite a
newer conversation state.
SSE subscription starts immediately on mount. Speech capability and world snapshot load in parallel
with independent 5-second deadlines, so a half-open metadata endpoint cannot delay live events or
local idle animation. A world metadata failure no longer marks a healthy event stream as offline;
late snapshots remain revision-fenced by the stage reducer.
Core admits at most 128 simultaneous Desktop SSE subscriptions and four for one session by default.
Concurrent admissions are serialized, excess clients receive HTTP 429, and completion, timeout, or
send failure releases the slot. This prevents reconnect storms from retaining unlimited 30-minute
emitters while leaving enough overlap for a normal connection handoff.
Core frames an oversized provider delta into ordered chunks of at most 16,384 UTF-16 code units,
without splitting a surrogate pair. This keeps every JSON SSE block below the Desktop parser's
65,536-character hard limit instead of causing a reconnect loop on one unusually large model chunk.
The renderer caps one conversation response at 128 Ki UTF-16 code units across streamed deltas and
the HTTP fallback. Crossing the limit cancels Core generation and pending TTS, releases the local
request owner, preserves only the bounded partial text, and returns the avatar to Idle with a
localized error instead of extending one DOM string without limit.

- `electron/main.ts`: transport and native desktop lifecycle
- `electron/preload.ts`: narrow IPC capability bridge
- `src/`: presentation only
- `src/stage/`: renderer-neutral state reducer and interchangeable Three/VRM
  character renderers
- `src/audio/`: local PCM recording, Core speech transport, playback, and
  presentation-only lip-sync analysis
- future avatar/world packages consume semantic Core events; they do not call
  LLM or memory providers directly
