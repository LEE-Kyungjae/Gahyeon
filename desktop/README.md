# Gahyeon Desktop

Desktop is a presentation client for Gahyeon Core. It does not own LLM,
memory, STT, TTS, behavior, or world-state decisions.

## Run locally

Start Core with the local-only transport enabled:

```bash
GAHYEON_HEADLESS_ENABLED=true ./gradlew bootRun
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

## Boundaries

- `electron/main.ts`: transport and native desktop lifecycle
- `electron/preload.ts`: narrow IPC capability bridge
- `src/`: presentation only
- `src/stage/`: renderer-neutral state reducer and interchangeable Three/VRM
  character renderers
- future avatar/world packages consume semantic Core events; they do not call
  LLM or memory providers directly
