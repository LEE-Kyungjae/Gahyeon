# Gahyeon Desktop

Desktop is a presentation client for Gahyeon Core. It does not own LLM,
memory, STT, TTS, behavior, or world-state decisions.

The interface detects Korean or English from the operating-system locale on
first launch. Users can switch languages from the conversation header; the
choice is persisted locally as `gahyeon.locale`.

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

To load a VRM model, copy `.env.example` to `.env` and set
`VITE_GAHYEON_VRM_URL` to a renderer-accessible URL. Without a model, the stage
uses a lightweight diagnostic character so world movement and camera behavior
remain testable.

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
