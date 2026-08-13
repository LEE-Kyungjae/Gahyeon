# Streaming STT transport v1

The provider-neutral transport described by ADR-0009 uses one persistent authenticated WebSocket
per Unreal client, separate from the character event socket. Only one utterance may be active on a
connection. JSON control/result frames validate against
`docs/contracts/gahyeon-streaming-stt-v1.schema.json`.

The Backend endpoint is `/api/gahyeon/unreal/stt/v1` (the application context path is `/api`). It
is registered only when headless, Unreal WebSocket and Streaming STT are explicitly enabled and a
`StreamingTranscriptionPort` provider bean exists:

```text
GAHYEON_HEADLESS_ENABLED=true
GAHYEON_UNREAL_WEBSOCKET_ENABLED=true
GAHYEON_UNREAL_STREAMING_STT_ENABLED=true
```

The normal Gahyeon client authentication policy protects the HTTP upgrade: remote clients require
`Authorization: Bearer $GAHYEON_CLIENT_TOKEN`; with no configured token, only loopback is allowed.
The embedded transport test installs the production servlet filter: an upgrade without the configured
Bearer token must fail with HTTP 401, while the same token authorizes both this socket and the speech
audio/status HTTP endpoints.
One WebSocket connection is permanently bound to the first `sessionId` it receives. Switching the
Core session on an established transport closes it with a policy violation.

## Lifecycle

```text
stt.stream.start
binary audio sequence 0
binary audio sequence 1
...
stt.transcript.partial *
stt.stream.end
stt.transcript.final | stt.stream.error
```

`stt.stream.cancel` may replace `end` at any point. A newer generation cancels the older active
stream before it starts. Partial results never start Cognition. A final result is accepted once for
the exact active `sessionId + streamId + generation` tuple.
Every accepted start also arms a Backend hard deadline (120 seconds by default, configurable with
`GAHYEON_UNREAL_STREAMING_STT_MAXIMUM_STREAM_SECONDS`, bounded to 5–300). Expiry cancels the exact
provider session with `timeout`, emits `provider_timeout`, and fences all later callbacks. This
reclaims both overlong speech and a half-open client that never sends `end` or `cancel`.
The Backend also admits at most 32 Streaming STT sockets by default
(`GAHYEON_UNREAL_STREAMING_STT_MAXIMUM_CONNECTIONS`, bounded to 1–1024). Capacity overflow closes
only the new socket with WebSocket 1013; existing event, renderer and transcription connections
continue running.
A newly admitted socket must submit its first valid `stt.stream.start` within 10 seconds
(`GAHYEON_UNREAL_STREAMING_STT_INITIAL_START_SECONDS`, bounded to 2–60). Otherwise it is closed and
its connection slot is reclaimed. Persistent sockets may remain idle between completed utterances;
the initial deadline exists specifically to prevent upgrade-only connections from exhausting the
instance bound.

Cancellation reasons are semantic and must not all be collapsed to `client_reset`:

| Reason | Owner/meaning |
| --- | --- |
| `barge_in` | a newer local VAD generation preempted the provider stream |
| `client_reset` | deliberate client/session teardown |
| `backpressure` | a bounded audio/transport queue rejected required data |
| `timeout` | the utterance exceeded a declared lifecycle deadline |
| `capture_error` | capture stopped before VAD end or its format/lifecycle became invalid |

Every reason terminates the exact active tuple and releases the provider session. A cancelled
utterance can never later produce an admitted final transcript.

The event socket's authenticated `client.hello` is the authority for `sessionId`, `worldId`,
`installationId` and display identity. Streaming start is rejected unless that Core session has an
active event connection. Provider partials are admitted directly to the Core Perception/Behavior
path. Provider final text is admitted directly to Cognition with request identity
`stt:<generation>:<streamId>`; Unreal does not need to echo it back for conversation processing.
If a client does echo the final event for presentation compatibility, the shared lifecycle gate
classifies it as a duplicate and does not dispatch Cognition twice.

## Binary audio frame

Every client binary frame is:

```text
offset  size  encoding
0       8     unsigned 64-bit audioSequence, network byte order
8       N     interleaved little-endian float32 PCM declared by stt.stream.start
```

`audioSequence` starts at zero and increases by exactly one. Payload bytes must be non-empty,
divisible by `channels * 4`, and no larger than 131,072 bytes. `end.lastAudioSequence` must match
the last fully accepted binary frame. A gap, duplicate, format change, binary frame without an
active stream, or queue overflow cancels the whole utterance; the server never transcribes a
silently truncated stream.
The servlet WebSocket container is configured explicitly for 131,080-byte binary messages
(8-byte sequence header plus the maximum PCM payload) and 65,536-byte control messages, so the
container cannot reject a protocol-valid frame before this lifecycle validation runs.
`UnrealStreamingSttTransportIntegrationTest` opens a real WebSocket against embedded Tomcat and
proves that the maximum binary frame reaches the provider intact. A configuration-object assertion
alone is not treated as transport acceptance evidence. The same test sends an oversized frame,
verifies that only that connection closes without reaching the provider, then proves a fresh socket
can immediately start a stream and deliver valid PCM. A complete bidirectional transport case also
runs `start -> binary audio -> end -> partial -> final` and verifies generation and result sequence
on the client-visible transcript envelopes. The barge-in transport case starts a newer generation
on the same socket, confirms the previous provider session receives `BARGE_IN`, injects a deliberately
late callback from it, and verifies that only the current generation transcript reaches the client.
A reconnect case closes the first socket, confirms its provider session receives `CLIENT_RESET`,
opens a new socket at the same durable generation, and proves a late callback from the closed socket
cannot appear beside the new connection's transcript.

## Ownership

Unreal owns capture, local VAD, Reflex and bounded transport queues. Core owns authentication,
provider sessions, resampling, transcript normalization and Conversation admission. Provider
credentials never cross into Unreal. Batch WAV fallback starts only with a later utterance after a
stream failure.

Without a provider bean the endpoint is not registered. A configured adapter that is not ready
(for example, a missing API key) rejects `start` with `provider_unavailable`; the batch path remains
the operational default until a streaming provider is enabled and measured.

## OpenAI Realtime provider (opt-in)

The first concrete Backend adapter uses the official `gpt-live-transcribe` transcription-session
protocol. It opens one asynchronous provider WebSocket per Gahyeon utterance, converts arbitrary
valid `float32le` capture rate/channels to mono PCM16 24 kHz, disables provider VAD, appends bounded
audio messages and commits exactly at the client VAD end. Provider deltas are accumulated into
current partial text; completed is normalized to one final. Since this model does not expose
confidence, stability is `0` rather than a fabricated score.

```text
ASSISTANT_ENABLED=true
ASSISTANT_STT_ENABLED=true
ASSISTANT_STT_API_KEY=...
ASSISTANT_STT_REALTIME_ENABLED=true
GAHYEON_HEADLESS_ENABLED=true
GAHYEON_UNREAL_WEBSOCKET_ENABLED=true
GAHYEON_UNREAL_STREAMING_STT_ENABLED=true
```

Optional tuning uses `ASSISTANT_STT_REALTIME_DELAY` (`minimal`, `low`, `medium`, `high`, `xhigh`),
`ASSISTANT_STT_REALTIME_MAXIMUM_PENDING_SENDS` and
`ASSISTANT_STT_REALTIME_FINAL_TIMEOUT_SECONDS`. Backend result delivery bounds use
`GAHYEON_UNREAL_STREAMING_STT_OUTBOUND_THREADS`,
`GAHYEON_UNREAL_STREAMING_STT_OUTBOUND_EXECUTOR_QUEUE_CAPACITY`, and
`GAHYEON_UNREAL_STREAMING_STT_OUTBOUND_PER_CONNECTION_QUEUE_CAPACITY`. Production enablement still
requires a real Korean
microphone evaluation and successful Unreal 5.6 compile/PIE; unit tests do not prove provider
availability, account access, latency or word error rate.

Provider result callbacks also cross a bounded Game Thread ingress (128 pending callbacks). An
overflow fails the entire active utterance as `backpressure`, sends an explicit cancel when
possible, closes that STT socket and reconnects. Partial or final text is never silently discarded
while the stream remains apparently healthy. Every callback is additionally fenced by socket
generation so an old connection cannot mutate a replacement connection.
Backend lock ordering is one-way: a provider callback may enter the synchronized transcription
state machine and then the connection sender, but connection deadline bookkeeping releases its
monitor before calling `isActive`, `timeout`, or `close` on the state machine. This prevents a
reconnect/start racing a late provider partial from deadlocking both the HTTP socket thread and
provider callback. The real WebSocket reconnect integration test exercises that race boundary.
The Backend provider adapter independently caps one fragmented provider event at 65,536 characters
and the accumulated transcript at 8,192 characters. Crossing either bound terminates and cleans up
the provider session rather than retaining an unbounded buffer.

Provider results do not call `WebSocketSession.sendMessage` inline. Each STT socket owns a bounded
serial queue drained by a shared dedicated executor (defaults: 4 workers, executor queue 32,
per-connection queue 64). A slow client therefore cannot hold the transcription state-machine
monitor or delay barge-in/cancel. Queue saturation, executor rejection, or delivery failure closes
only that STT socket and releases its provider session; result ordering is retained per connection.

The provider final is acknowledged to Unreal only after direct Core Cognition admission succeeds.
If the bounded Cognition executor returns `BACKPRESSURE` (or dispatch throws), Backend rolls back
the final lifecycle and fails the active STT utterance instead of sending a successful final with
no LLM/TTS work behind it. The client therefore leaves Thinking through the existing STT-failure
generation advance rather than waiting for a response that was never admitted.

## Operations metrics

The Backend records monotonic server-side spans without using client wall-clock timestamps. Metric
tags are fixed allowlists; session, stream, transcript and provider payload values are never tags.

| Metric | Meaning |
| --- | --- |
| `gahyeon.unreal.stt.streaming.first.partial` | accepted start to first provider partial |
| `gahyeon.unreal.stt.streaming.duration{result}` | accepted start to final, error or cancellation |
| `gahyeon.unreal.stt.streaming.failures{code}` | bounded protocol/provider failure-code counts, including backpressure |
| `gahyeon.unreal.stt.streaming.connections` | currently admitted Streaming STT sockets |
| `gahyeon.unreal.stt.streaming.connection.rejected{reason=capacity}` | new sockets isolated at the instance connection bound |
| `gahyeon.unreal.stt.streaming.outbound.detached{reason}` | slow/failed socket isolated for `queue_full`, `executor_rejected`, or `delivery_failed` |

These metrics diagnose Backend/provider behavior. End-user microphone-to-expression and
microphone-to-audio budgets remain Unreal monotonic traces because a server-only span cannot prove
render or playback latency.

## End-to-end evaluation

`desktop/tools/evaluate-streaming-stt.mjs` exercises the production transport rather than calling
the provider directly. It authenticates and opens the normal event socket, waits for
`server.welcome`, opens the separate STT socket, converts PCM16/float32 WAV to the exact binary
float32 frame contract, paces chunks at audio speed and commits at the synthetic VAD end. The
atomic report includes source SHA-256, partial history, normalized Korean CER, start-to-first-partial,
start-to-final and VAD-end-to-final latency. Its contract is
`docs/contracts/gahyeon-streaming-stt-evaluation-v1.schema.json`.

```bash
cd desktop
GAHYEON_CLIENT_TOKEN=... npm run evaluate:streaming-stt -- \
  --event-url ws://127.0.0.1:8080/api/gahyeon/unreal/v1 \
  --stt-url ws://127.0.0.1:8080/api/gahyeon/unreal/stt/v1 \
  --wav /absolute/path/korean-evaluation.wav \
  --expected '안녕하세요. 오늘 상태를 확인하겠습니다.' \
  --output ../artifacts/streaming-stt-evaluation/case-01.json
```

Run multiple fixed utterances before production enablement and calculate p50/p95 from the retained
case reports. A single successful file is connectivity evidence, not latency or Korean accuracy
acceptance. The evaluator intentionally keeps the event socket alive so Core session admission and
direct final-to-Cognition dispatch are part of the test.

The suite summarizer uses nearest-rank percentiles so a small number of slow tail samples are not
interpolated away. Its initial acceptance profile requires 20 trials, at least 10 distinct WAV
identities, zero failed trials, first-partial p95 at most 1,200 ms, VAD-end-to-final p95 at most
1,500 ms, mean CER at most 0.12, worst CER at most 0.30 and partial-result coverage of at least 80%.
All limits can be overridden explicitly, but the chosen values remain in the summary report.

```bash
cd desktop
npm run evaluate:streaming-stt:summary -- \
  --input ../artifacts/streaming-stt-evaluation/cases \
  --output ../artifacts/streaming-stt-evaluation/summary.json
```

Exit code `0` means every acceptance gate passed, `2` means the reports were valid but rejected,
and `1` means the input/report contract itself was invalid. Put only individual case reports in the
input directory; keep the aggregate summary outside it.

For unattended measurement, use a strict JSONL suite. Paths are resolved relative to the suite;
IDs are restricted to safe filename characters, repeats are limited to 20 per source and the whole
run is capped at 200 sequential trials. Successful reports are reused only when the expected text
and current WAV SHA-256 still match. Matching failures remain durable for diagnosis unless
`--retry-failures true` is explicitly supplied.

The repository includes a fixed Korean baseline at
`artifacts/streaming-stt-korean-baseline/suite.jsonl`: 10 distinct immutable Voicebox WAVs with
manifest-backed expected text and two repeats each. Before launching a paid provider run, use the
preflight. It reports only `configured`/`missing` for credentials and never prints a key.

```bash
python3 scripts/preflight_streaming_stt_evaluation.py \
  --suite artifacts/streaming-stt-korean-baseline/suite.jsonl
```

Preflight exit `0` proves that the six required feature flags, provider credential, 10 files and
20 trials are present. It does not prove provider access or quality; that evidence exists only
after the production transport suite completes.

```jsonl
{"id":"neutral-01","wav":"wav/neutral-01.wav","expected":"안녕하세요. 오늘 상태를 확인하겠습니다.","repeats":2}
{"id":"question-01","wav":"wav/question-01.wav","expected":"지금 바로 실행할까요?","repeats":2}
```

```bash
cd desktop
GAHYEON_CLIENT_TOKEN=... npm run evaluate:streaming-stt:suite -- \
  --event-url ws://127.0.0.1:8080/api/gahyeon/unreal/v1 \
  --stt-url ws://127.0.0.1:8080/api/gahyeon/unreal/stt/v1 \
  --suite ../artifacts/streaming-stt-korean-baseline/suite.jsonl \
  --output ../artifacts/streaming-stt-korean-baseline/run-01
```

The suite runner opens one client pair at a time, atomically checkpoints each case under `cases/`
and writes `summary.json` after the final trial. It never launches concurrent provider streams.
