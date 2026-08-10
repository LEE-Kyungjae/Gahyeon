# Looking Glass display adapter

Gahyeon treats Looking Glass Go as an optional light-field output for the same
Desktop stage. It is not a second Core, agent, session, behavior engine, or
world-state owner.

## Integration choice

The Desktop renderer uses Looking Glass's official WebXR library rather than
the legacy HoloPlay/Core JS stack. The library converts the existing Three.js
perspective scene into multi-view light-field output through Looking Glass
Bridge.

Official references:

- <https://lookingglassfactory.com/webxr>
- <https://lookingglassfactory.com/software>
- <https://www.npmjs.com/package/@lookingglass/webxr>
- <https://lfdocs.lookingglassfactory.com/software/looking-glass-bridge-sdk/native-function-reference>

## Setup

1. Connect Looking Glass Go in desktop mode.
2. Install and run Looking Glass Bridge.
3. In `desktop/.env`, enable the adapter:

   ```dotenv
   VITE_GAHYEON_LOOKING_GLASS=true
   ```

4. Start Desktop, click `ENABLE LOOKING GLASS`, then use the generated
   `ENTER VR` button.

The SDK is dynamically imported only after the first button click. With the
flag disabled, no WebXR polyfill is loaded. With the flag enabled but no device
or Bridge present, the monitor renderer, conversation, audio, and World State
continue to operate normally.

## Shared-state invariant

Both outputs use the same in-memory Three.js scene and the same reduced
`StageState`:

```text
Core World/Event snapshot
          │
          ▼
      StageState
          │
          ▼
   Shared Three.js scene
      ├─ normal perspective frame → Desktop monitor
      └─ WebXR multi-view frame    → Looking Glass Bridge → Go
```

No Looking Glass calibration, view count, quilt layout, or device connection
state is persisted in Gahyeon Core.

## Verification status

- Production bundle and lazy WebXR chunk: verified.
- Adapter opt-in and `ENTER VR` creation without Bridge: verified.
- Desktop fallback with Bridge absent: verified.
- Calibrated light-field output, depth tuning, and performance on physical Go:
  requires the target device and Looking Glass Bridge and remains a hardware
  acceptance test.
