# AIRI Desktop reference analysis

This project uses [Project AIRI](https://github.com/moeru-ai/airi) as a design
reference, not as an AI-runtime dependency. The review was refreshed against
the upstream repository on 2026-08-10.

## What is relevant

AIRI validates several choices that fit Gahyeon Desktop: a web-technology
presentation layer, separate web and native clients, VRM animation, browser
audio input, talking detection, WebAudio, and an extensible stage/UI boundary.
Its repository separates apps, stage engines, integrations, packages, server,
and services. That separation is more valuable to Gahyeon than copying an
individual component.

| AIRI capability | Gahyeon decision | Reason |
| --- | --- | --- |
| Vue/web presentation | Adopted independently | Gahyeon Desktop uses Vue, Electron and Three.js. |
| VRM control and animation | Adopted through official Pixiv packages | Avoids coupling to AIRI's stage lifecycle and stores no AIRI runtime state. |
| Browser microphone/WebAudio | Reimplemented | Audio crosses Gahyeon's STT/TTS ports; AIRI providers are not imported. |
| Stage web/native split | Adopted as a boundary | Desktop and optional Looking Glass render the same semantic `StageState`. |
| AIRI LLM, memory and provider stack | Rejected | Gahyeon Core already owns conversation, memory, tools and provider adapters. |
| AIRI embedded database | Rejected | Persistent identity, events, memory and World State remain server-side. |
| AIRI source copying | Rejected | Current Desktop code is purpose-built and dependency-minimal. |
| Live2D | Deferred | VRM is the current Gahyeon character contract; another renderer can be added later. |

## Dependency boundary

```text
Gahyeon Core semantic events
             │
             ▼
        StageState reducer
          ┌──┴───────────┐
          ▼              ▼
 Three/VRM renderer   Looking Glass adapter
```

The renderer never decides emotion, activity, destination, memory, or dialogue.
It maps Core state to cameras, meshes, animation clips, expressions, and
visemes. This preserves the ability to replace Three.js or reuse selected AIRI
presentation ideas without migrating Gahyeon's identity or AI system.

## Upstream ideas worth revisiting

- renderer lifecycle observability and asset caching;
- automatic blink, gaze, and idle eye movement beyond current activity poses;
- a presentation plugin contract once at least two real plugins exist;
- mobile/PWA presentation only after Desktop transport and account linking are
  stable.

Any future adoption must be evaluated at the package/dependency level and must
not introduce a second conversation, memory, STT, TTS, or world authority.
