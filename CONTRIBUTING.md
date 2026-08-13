# Contributing to Gahyeon

Thanks for helping build Gahyeon. Keep changes small, evidence-backed, and compatible
with the separation between Core and its presentation adapters.

## Before opening a change

1. Search existing issues and pull requests.
2. Base changes on `develop`; production changes reach `main` through a reviewed PR.
3. Do not commit credentials, source voice data, model checkpoints, generated character
   drafts, or licensed third-party assets.
4. Preserve existing Discord behavior while moving reusable logic toward Core ports.
5. Do not present source-only or simulated evidence as physical Unreal, MetaHuman,
   audio-device, or Looking Glass acceptance.

## Local verification

Run the checks relevant to the files you changed.

### Backend and Core

```bash
./gradlew test
python3 scripts/verify_core_platform_boundaries.py
./scripts/test_smoke_headless_core.sh
```

### Desktop

```bash
cd desktop
npm ci
npm test
npm run build
```

### Unreal contracts

```bash
./scripts/verify_unreal_stage_scaffold.sh
./scripts/test_unreal_runtime_core.sh
./scripts/verify_unreal_protocol_contract.sh
```

These checks do not replace UE 5.6, MetaHuman, packaged-build, or physical-device
acceptance where the relevant quality gate requires it.

### Documentation

```bash
python3 scripts/test_verify_readme_i18n.py
python3 scripts/verify_readme_i18n.py
```

## Pull requests

- Explain the user-visible outcome and the architectural boundary affected.
- Include tests for behavior changes and record any hardware or provider proof that
  remains pending.
- Keep generated artifacts out of source commits unless a documented artifact contract
  explicitly requires a small, redistributable fixture.
- Use clear commit messages and avoid mixing unrelated subsystems in one commit.

By contributing, you agree that your contributions are licensed under the project's
[MIT License](LICENSE).
