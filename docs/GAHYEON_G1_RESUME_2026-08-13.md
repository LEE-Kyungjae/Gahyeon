# Gahyeon G1 resume checkpoint — 2026-08-13

This document is the authoritative resume point for the current Blender character work.
It records what was actually produced and what must remain unclaimed.

## Current decision

- Long-term quality plan: 40 closed authoring/QA passes.
- Current progress: approximately pass 8.5 of 40.
- Current best artifact: `artifacts/gahyeon-ch/models/gahyeon-g1-mpfb-v10.blend`.
- Review state: `draft-for-human-review` only.
- `artifacts/gahyeon-ch/g1-review.json` remains `draft`, with zero evidence,
  no `modelArtifact`, and no approvals.
- Do not register v10, transition G1 to `candidate`, or record an approval yet.

The v10 model is a real humanoid artifact, but it is not a likeness-approved G1
candidate and is far from the final AAA target.

## Verified v10 artifact

| Property | Value |
| --- | --- |
| Blender | 5.2.0 LTS, build `fbe6228777e7` |
| Model SHA-256 | `d84aa6b616a690cb54b45ea3f054291e091f1bc17b30ac8ba1b2cef853e958c8` |
| Model bytes | `75,371,467` |
| Authoring script SHA-256 | `27830147f10d6bced2d361b64ecd9eb64c2a7d95e01015c6c32c9016f414d041` |
| Scene-plan SHA-256 | `6f5dbd0e895f90341c586417c9b60f22bb1b08f53c824c477f78fa055627bba7` |
| Mesh objects | 24 |
| Base vertices / polygons | 28,086 / 26,616 |
| Armature | 1 armature, 53 bones |
| Height | 172.0 cm |
| Hair guides | 252 curves / 4,032 points |
| Evaluated groom | 10,191 curves / 621,651 points |
| Pose | neutral A-pose |

The model and provenance hashes agree. The file opens headlessly in Blender 5.2.
The groom evaluates with no Geometry Nodes warnings.

## Work completed in passes v1–v10

- Built an actual MPFB humanoid instead of treating an empty/bootstrap scene as a model.
- Preserved the canonical identity authority order: reference 03 is the neutral face
  master, references 06/07/08 constrain depth, and 16/19/20 constrain body shape.
- Created a 172 cm grounded body with a 53-bone game-engine rig.
- Kept face/body topology stable while applying identity changes before rig creation.
- Added fitted eyes, eyebrows, eyelashes, tongue, teeth, base clothing, sneakers,
  and an open white/turquoise jacket blockout.
- Fixed shoes that previously penetrated the ground and shortened their silhouette.
- Added rigid G1 bindings to jacket and shoe pieces instead of leaving them root-only.
- Corrected dark evidence lighting and preserved transparent official-evidence output.
- Replaced the helmet-like `long01` mesh hair with the official MakeHuman Hair Editor
  CC0 `straight_hair_to_shoulder` Blender Curves template.
- Pinned the Hair Editor archive and blend checksums:
  - archive: `39420056faba6aaa0726a5168c9c41f2d01e278a12e216c0385e8f13d4d98ab7`
  - `hair.blend`: `93aedaef061dc14618a0fcfad33c3df816fa755e8093381cbdfd0fd4c34527f5`
- Baked the meter-to-centimeter conversion into guide coordinates, all exposed
  Geometry Nodes distance inputs, and the nested interpolation radius.
- Added the required 19,158-point `rest_position` attribute and retargeted the groom
  to the MPFB body's `UVMap` and deformation surface.
- Fixed the sparse-groom regression found in v8/v9. v10 visibly covers the scalp on
  a neutral gray background; this is not a black-background illusion.
- Partitioned eye, brow, cheek, lip, nose, and lower-face sculpt regions to reduce
  accidental double transformation.
- Produced five viewable v10 previews under
  `artifacts/gahyeon-ch/g1-preview-v10/`.

## Current visual truth

v10 is technically stronger than v7, especially in groom structure, but it still
does not resemble the canonical face closely enough.

Blocking defects:

1. The face still reads as a generic, older character. The eye sockets, nose/mouth
   relationship, jaw softness, and youthful mid-face need an authored sculpt pass.
2. The fitted eye texture has oversized, dark irises and lacks a production eye
   assembly with semantic sclera/cornea/iris/pupil/tear-line separation.
3. The groom is now dense and near-black, but it remains too straight and blunt.
   It needs canonical center-part clumps, face-framing locks, layered tips, loose
   waves, and a better rear/top design.
4. The jacket is still a visibly blocky G1 construction. Sleeves, panels, hood,
   waistband, shorts, socks, and sneakers require proper garment topology and weights.
5. Skin uses a 2K MPFB diffuse and procedural response. It has no authored 4K
   normal/displacement/roughness set, pore sculpt, tear line, or facial peach fuzz.
6. The 53-bone rig has no facial bones or expression shape keys.

The hair-hidden v10 QA render produced these macOS Vision ratios. They are useful
directional evidence, not identity truth:

| Ratio | Canonical 03 | v10 hair-hidden |
| --- | ---: | ---: |
| eye separation / face box | 0.3657 | 0.3939 |
| mean eye width / face box | 0.1647 | 0.1728 |
| mean eye height / face box | 0.0629 | 0.0631 |
| nose width / face box | 0.1610 | 0.1753 |
| mouth width / face box | 0.2592 | 0.2989 |
| mouth height / face box | 0.1135 | 0.1495 |
| lip-to-chin / face box | 0.2319 | 0.2527 |

Do not measure the hair-visible render with Vision: foreground strands alter its
face bounding box and produce invalid comparisons.

## Exact next work

Pass 9 is not closed. Resume with a new `v11` output; never overwrite v10.

1. Create a hair-hidden neutral face preview from v10 and overlay it with reference
   03. Adjust the actual lip/jaw/eye regions from the overlay instead of adding more
   blind global target values.
2. Move the fitted eyeballs inward with the MPFB eye translation target and verify
   pupil centers, not only eyelid vertices. Reduce eye width without reducing the
   already-correct eye height.
3. Make lip width and height changes own the complete lip group, then verify both
   the closed seam and profile depth against 06/07/08.
4. Preserve the v10 groom's unit conversion and interpolation-radius fixes. Modify
   guide shape only: introduce smooth long-range S-waves and layered ends without
   reopening scalp coverage.
5. Replace the box-panel jacket with continuous torso/sleeve garment surfaces and
   transferred weights. Keep the current blockout only as a silhouette reference.
6. Render face front, three-quarter, both profiles, hair front/side/rear/top, and
   body front before deciding whether to render the formal 15-view set.
7. Only after visual QA passes, render the 15 sealed views, package the submission,
   register the model/evidence in `g1-review.json`, and transition to `candidate`.
   Approvals must remain empty until the user reviews it.

## Recommended Codex model cadence

Do not spend the entire 40-pass plan at maximum reasoning effort.

- Use GPT-5.6 Sol at `low` or `medium` for deterministic author/render/verify loops.
- Raise Sol to `high` for identity-sculpt decisions, visual-delta diagnosis, and
  difficult Blender or Geometry Nodes failures.
- Use Sol `ultra` for milestone audits around passes 10, 20, 30, and 40, where a
  missed visual or lineage defect would invalidate substantial downstream work.
- If only one setting is available for an uninterrupted session, prefer Sol
  `high`; it is the safer compromise for this mixed visual and technical workload.

Quality must still be decided from rendered comparisons and gate evidence. A higher
reasoning setting is not a substitute for the 40 closed authoring/QA passes.

## Reproduction inputs

The public repository intentionally does not add the raw canonical source images in
this checkpoint. The local identity and modeling manifests used by v10 have hashes:

- `identity-reference.json`: `41653f14ce045ad86bcf703a806cd61a416ce284a3c831917bac899c901b0179`
- `modeling-input.json`: `914628a1597a0deb30fc172f5023e2bb2674ad9b141d404b0ca342f13544e29f`

Required local tools/assets:

- Blender 5.2.0 LTS with MPFB 2.0.17 (`80919fa4682335c41847f761a4d79dcad4124732`)
- MakeHuman system assets at `/tmp/gahyeon-mh-system-assets`
- Hair Editor `hair.blend`, verified against the checksum above
- `artifacts/gahyeon-g1-authoring/gahyeon-g1-authoring-bootstrap-v2.blend`
- `artifacts/gahyeon-g1-authoring/scene-plan-v2.json`

Author a new revision with unique fail-no-overwrite paths:

```sh
blender --background \
  artifacts/gahyeon-g1-authoring/gahyeon-g1-authoring-bootstrap-v2.blend \
  --python scripts/blender_author_gahyeon_g1.py -- \
  --asset-root /tmp/gahyeon-mh-system-assets \
  --hair-template /tmp/gahyeon-haireditor/hair/haireditor/hair.blend \
  --identity artifacts/gahyeon-ch/identity-reference.json \
  --modeling artifacts/gahyeon-ch/modeling-input.json \
  --output artifacts/gahyeon-ch/models/gahyeon-g1-mpfb-v11.blend \
  --provenance-output artifacts/gahyeon-ch/models/gahyeon-g1-mpfb-v11.provenance.json \
  --revision g1-mpfb-v11
```

When a revision is visually ready, the formal evidence command is:

```sh
blender --background artifacts/gahyeon-ch/models/gahyeon-g1-mpfb-v11.blend \
  --python artifacts/gahyeon-g1-authoring/handoff-v2/tools/blender-render-g1-evidence.py -- \
  --plan artifacts/gahyeon-g1-authoring/scene-plan-v2.json \
  --output-dir artifacts/gahyeon-ch/g1-evidence-v11
```

## Repository safety

- The worktree contains hundreds of unrelated backend, Desktop, Unreal, voice, and
  documentation changes from other concurrent work. Do not reset, clean, stash, or
  bulk-stage it.
- Stage G1 files by explicit path only.
- v1–v9 are retained locally as audit drafts but are not the resume artifact.
- The GitHub repository is public. Do not add raw identity reference images without
  a separate explicit publication decision.
