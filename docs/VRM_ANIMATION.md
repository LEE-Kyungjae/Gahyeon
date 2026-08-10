# VRM activity animation

Gahyeon Core chooses semantic activities. Desktop maps those activities to
licensed VRM Animation (`.vrma`) clips and blends them in the presentation
layer.

Supported activity keys:

```text
idle
walk
sit
read
sleep
work
look_outside
relax
attention
conversation
```

## Animation manifest

Create a JSON manifest based on
`desktop/public/animations/manifest.example.json`, host the referenced files,
and set:

```dotenv
VITE_GAHYEON_VRMA_MANIFEST=/animations/manifest.json
```

Each file must use the VRMC_vrm_animation format. Desktop loads it with the
official `@pixiv/three-vrm-animation` package, retargets it to the active VRM
humanoid, loops it, and cross-fades activity changes over 350 ms.

Official reference:

- <https://pixiv.github.io/three-vrm/docs/modules/three-vrm-animation>
- <https://www.npmjs.com/package/@pixiv/three-vrm-animation>

## Fallback behavior

Missing or invalid manifest entries do not prevent the avatar from loading.
The renderer supplies deterministic procedural poses for every supported
activity, including opposing-arm/leg walk cycles, seated reading/work poses,
sleep, attention, conversation motion, and idle breathing. While the character
traverses a waypoint path, presentation temporarily uses the walk animation and
returns to the Core-selected activity at arrival.

## Asset policy and acceptance

Animation files are deliberately not copied from AIRI or third-party packs.
Before adding an asset, record its author, source URL, license, redistribution
permission, and whether commercial use is allowed. Validate each clip against
the actual Gahyeon VRM for:

- foot sliding and floor penetration;
- seated alignment with chair, desk, and bed interaction points;
- arm/body clipping;
- VRM 0.x and VRM 1.0 orientation;
- expression and lip-sync coexistence;
- cross-fade discontinuities;
- Desktop and Looking Glass frame time.
