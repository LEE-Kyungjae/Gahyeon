# AIRI reference assessment for Gahyeon Desktop

Assessment date: 2026-08-10

Gahyeon uses AIRI as a presentation architecture reference, not as an AI
runtime dependency or a source tree to copy wholesale.

## Relevant upstream structure

The current AIRI repository separates its Electron desktop shell
(`stage-tamagotchi`) from shared stage UI packages. Its documented desktop
stack is Electron, Vue, Vite, TypeScript, Pinia, VueUse, Eventa, UnoCSS, and
Vitest. AIRI also maintains distinct model/rendering concerns for VRM and
Live2D and is extracting agent runtime logic away from stage UI.

Primary references:

- <https://github.com/moeru-ai/airi>
- <https://github.com/moeru-ai/airi/blob/main/AGENTS.md>
- <https://github.com/moeru-ai/airi/blob/main/package.json>
- <https://github.com/moeru-ai/airi/issues/520>

## Adopted principles

1. Keep the native desktop lifecycle separate from the visual stage.
2. Use a narrow preload bridge; do not expose Node or provider credentials to
   the renderer.
3. Treat avatar renderers as consumers of semantic events.
4. Keep the stage reusable between desktop-window and future display surfaces.
5. Make model loading, expression mapping, animation, lip sync, and camera
   independent modules rather than one character component.

## Intentionally not adopted

- AIRI's LLM, memory, speech, provider, authentication, or agent runtime code;
- its plugin protocol as a Gahyeon Core protocol;
- Live2D in the first renderer milestone;
- direct coupling between UI stores and AI providers;
- a repository fork or copied monorepo dependency graph.

Gahyeon already owns those AI capabilities in Core. Importing duplicates would
create competing state authorities and violate the headless requirement.

## Gahyeon Desktop boundaries

```text
Electron main
  └─ Core transport (HTTP + resumable SSE)
       └─ preload capability bridge
            └─ Vue presentation shell
                 ├─ conversation UI
                 └─ future stage runtime
                      ├─ VRM renderer
                      ├─ expression / lip sync
                      ├─ animation controller
                      ├─ world renderer
                      └─ camera controller
```

The renderer receives commands such as `avatar.expression` or
`character.move`; it must never decide why Gahyeon is happy or where she wants
to go. Those decisions remain in Core.

## Next extraction targets

1. Define avatar, behavior, and world-state event schemas in Core.
2. Add a renderer-neutral stage store that reduces events into presentation
   state.
3. Integrate Three.js and `@pixiv/three-vrm` behind a `CharacterRenderer`
   interface.
4. Add deterministic expression and viseme mixers before microphone/TTS audio
   plumbing.
5. Introduce the Looking Glass renderer only after the desktop renderer shares
   a stable world snapshot and camera-independent scene model.
