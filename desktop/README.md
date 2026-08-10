# Gahyeon Desktop

Desktop is a presentation client for Gahyeon Core. It does not own LLM,
memory, STT, TTS, behavior, or world-state decisions.

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

Set `GAHYEON_CORE_API_URL` when Core is not available at
`http://127.0.0.1:8080/api`.

To load a VRM model, copy `.env.example` to `.env` and set
`VITE_GAHYEON_VRM_URL` to a renderer-accessible URL. Without a model, the stage
uses a lightweight diagnostic character so world movement and camera behavior
remain testable.

Desktop queries Core speech readiness at startup. When configured, the mic
button records mono PCM WAV locally, sends it through Core STT, and submits the
transcript as a normal conversation. Replies are segmented through Core TTS and
played through the system speaker. Playback amplitude drives the renderer's
`aa` viseme; no microphone or audio-provider object enters Gahyeon Core.

The diagnostic world contains bedroom, living-room, workspace, connecting
hallways, and the interaction objects used by the deterministic behavior
policy. Movement follows doorway waypoints while the camera follows the actual
character position rather than the final destination.

Looking Glass support is opt-in through `VITE_GAHYEON_LOOKING_GLASS=true` and
requires Looking Glass Bridge. The adapter is lazy-loaded and renders the same
Three.js scene through WebXR; see `docs/LOOKING_GLASS.md` for setup and hardware
verification status.

## Boundaries

- `electron/main.ts`: transport and native desktop lifecycle
- `electron/preload.ts`: narrow IPC capability bridge
- `src/`: presentation only
- `src/stage/`: renderer-neutral state reducer and interchangeable Three/VRM
  character renderers
- `src/audio/`: local PCM recording, Core speech transport, playback, and
  presentation-only lip-sync analysis
- future avatar/world packages consume semantic Core events; they do not call
  LLM or memory providers directly
