# Gahyeon RuntimeCore

Engine-independent C++20 implementation of Gahyeon's real-time character intent runtime.
The source of truth lives in the `GahyeonRuntimeCore` Unreal module under
`../GahyeonStage/Source/GahyeonRuntimeCore`; this CMake project compiles those exact sources
without Unreal. Domain sources contain no Unreal, rendering, network, LLM, or provider types.

```bash
./scripts/test_unreal_runtime_core.sh
```

The Unreal project consumes the same sources as its `GahyeonRuntimeCore` module. Unreal
components translate protocol messages and local sensor
events into `CharacterIntent`; Animation Blueprint and Control Rig consume resolved
channels. The game thread must never wait for Cognition or network futures.

Network, STT, and Cognition producer threads write only to `IntentMailbox`. The Unreal
game thread drains that bounded mailbox and is the sole owner of `IntentRuntime` and
`RealtimeCharacterCoordinator`. Overflow is observable through `DroppedCount` rather
than blocking a producer thread. Under saturation, Reflex may evict the oldest lower
layer item; Cognition can never evict Reflex.

A current-generation partial transcript is also a local Reflex signal. While the voice phase is
still Listening it refreshes the short-lived `attention=user` intent before attempting any network
send. Partial callbacks after VAD end or from an older generation cannot revive Listening behavior.

`ProtocolMessageTranslator` accepts an engine-normalized semantic message. It deliberately
does not parse JSON; the future Unreal adapter uses Unreal's JSON module and passes typed
values into RuntimeCore. LLM completion does not enter Speaking. Only current-generation
`speech.prepared` enters the ordered `SpeechQueue`; the coordinator enters Speaking only
when the audio subsystem actually starts playback. A new generation clears queued audio.

`ProtocolEventRuntime` is the game-thread application boundary above that translator. During
reconnect replay, an authoritative message with a generation ahead of the local runtime
advances the intent cancellation watermark and the speech queue atomically before publishing
the message. It returns the old active utterance ID for immediate engine-side stop. Lower
generations are rejected, and unknown future event types cannot advance the watermark.

`ProtocolIngressMailbox` and `ProtocolNetworkIngressAdapter` are the socket-thread boundary.
Durable events can evict stale ephemeral observations but are never silently discarded; an
all-durable overflow tells the adapter to reconnect so replay recovers the event.
`ProtocolGameThreadDispatcher` is the sole consumer and advances `ReplayCursorRuntime` only
after isolated application. A backpressured event is requeued without cursor advance.

`ProtocolNetworkEgressRuntime` exposes normalized `client.ack` and action-completion commands.
It refuses to expose either until the SaveGame callback confirms the corresponding cursor or
outbox state was persisted. Socket send only schedules retry; terminal Backend ACK is the
removal boundary. JSON, WebSocket calls, and disk I/O remain outside RuntimeCore.

`ClientRuntimeSaveStateCodec` defines the versioned SaveGame data boundary for durable cursor,
interaction-generation watermark, pending completions, retry delays, and rejected-command
diagnostics. Schema v2 prevents reconnect/restart from rewinding stale-result admission; v1
migrates the missing generation to zero. It stores remaining retry duration rather than monotonic
timestamps and fails closed on future schemas.

`ReplayCursorRuntime` owns the client resume watermark independently from the WebSocket
implementation. A durable event is completed only after its isolated handling finishes;
unknown events still complete so extensions cannot create an infinite replay loop. A
`stream.cursor` may jump over events filtered by session/world scope. The safe cursor is
persisted before `client.ack` is sent, survives disconnect, and never regresses.

`WorldStateRuntime` validates the complete hello snapshot and swaps it atomically by world
revision. Older replay cannot regress the snapshot; equal revision with different content is
reported as a conflict rather than producing a mixed world. `ConnectionConvergenceRuntime`
measures reconnect-to-snapshot time using the engine monotonic clock and exposes the RT-06
two-second timeout to the debug overlay/reconnect policy.

`LatencyTrace` is a game-thread-owned bounded ring recorder for RT acceptance. It tracks
p50/p95/p99/worst, total budget violations, and bounded pending spans without retaining
unbounded history. VAD starts spans locally, while the animation bridge and audio-device
callback close them only when Listening is actually presented and interrupted audio is
actually stopped. Reconnect and viseme runtimes record snapshot convergence and cue onset
from the same monotonic/audio-position clocks used by presentation.

`finalSegment` closes only one utterance. `speech.sequence.ended` closes the complete
streaming response, and `SpeechQueue::SequenceDrained()` becomes true only after that
marker has arrived and all prepared audio has been consumed. A new generation resets both.

`SpeechPlaybackCoordinator` is the game-thread bridge to Unreal's audio component. Merely
receiving or acquiring prepared audio does not enter Speaking. The audio component must call
`PlaybackStarted`; completion returns to Idle only when the sequence marker has arrived and
the queue plus active audio are drained. `SetGeneration` returns the active utterance ID that
the engine must stop during barge-in.

`LipSyncRuntime` starts from that same real audio-device callback. Timed cues are sampled from
the audio component's playback position, with up to two overlapping semantic visemes for
coarticulation. An empty timeline uses a noise-gated, attack/release-smoothed RMS jaw signal.
Sampling only describes the desired mouth pose and never records an acceptance result.
`ConfirmVisemePresented` records audio-to-mouth onset only after Presentation has applied a
bound morph target or a Control Rig/Anim Blueprint has explicitly acknowledged the control.
Completion and a strictly newer generation clear mouth ownership immediately; duplicate or
regressing watermarks are no-ops.

`VoiceActivityDetector` applies RMS hysteresis plus attack/release windows using a monotonic
clock. `VoiceInteractionController` turns its local start event into a new generation and
returns the currently playing utterance immediately; this path never waits for STT, WebSocket,
or Cognition. The end event enters Thinking while ambient intents continue.

`RealtimeCharacterCoordinator::Advance` is the local Thinking watchdog. On expiry it advances
the cancellation generation and returns to Idle; `VoiceInteractionController::Tick` resets
speech ownership and returns the generation that the network adapter sends as
`interaction.generation.advanced`. Late LLM and TTS results then fail both locally and in the
Backend dispatcher.

`AmbientMotionRuntime` produces normalized breathing, blink, saccade, micro-head, and
weight-shift signals without any Backend object. It uses a monotonic clock and seeded PRNG,
so animation tests and captures are reproducible. Unreal samples it every frame and blends
these low-amplitude outputs below attention, gesture, facial, and lip-sync layers.

`AttentionRuntime` accepts a character-local user vector (forward/right/up), moves eyes with
a 60ms response and lets the head follow at 180ms, then fades stale tracking back to ambient.
The animation bridge composes eye output as `ambient * (1 - TrackingWeight) + Attention`;
Attention values already include confidence/fade. Camera/face tracking calls this locally
before mirroring `perception.user.pose` to the Backend.

`EmotionRuntime` retains the complete semantic dimension map plus valence/arousal/dominance,
blends targets continuously, optionally holds and releases them, and never depends on the
conversation phase. `GestureRuntime` receives only a semantic intent and selects a weighted,
seeded local profile variant after posture/intensity/cooldown checks. Noninterruptible gestures
finish locally; a newer generation clears only generation-bound gestures, not ambient motion.

The standalone tests intentionally mirror
`desktop/src/runtime/realtime-character-coordinator.test.ts`. Both suites must remain
green when transition or arbitration rules change.
