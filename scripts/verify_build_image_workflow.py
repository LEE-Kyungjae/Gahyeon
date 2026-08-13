#!/usr/bin/env python3
"""Prevent non-main image/infra mutation and unsupported image architectures."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD_WORKFLOW = ROOT / ".github/workflows/build-image.yml"
VERIFY_WORKFLOW = ROOT / ".github/workflows/verify-production.yml"
CONTAINER_SMOKE = ROOT / "scripts/smoke_headless_container.sh"

PUBLISH_CONDITION = (
    "if: github.ref == 'refs/heads/main' && (github.event_name == 'push' || "
    "(github.event_name == 'workflow_dispatch' && inputs.push_image == true))"
)
INFRA_CONDITION = (
    PUBLISH_CONDITION
    + " && env.HAS_INFRA_REPO_TOKEN == 'true'"
)


def publication_allowed(ref: str, event: str, push_image: bool) -> bool:
    """Executable truth table mirrored by the exact workflow expression."""
    return ref == "refs/heads/main" and (
        event == "push" or (event == "workflow_dispatch" and push_image)
    )


def named_steps(workflow: str) -> dict[str, str]:
    """Return top-level step bodies without depending on a YAML package in CI."""
    matches = list(re.finditer(r"(?m)^      - name: (.+)$", workflow))
    return {
        match.group(1): workflow[match.start(): matches[index + 1].start()
                                if index + 1 < len(matches) else len(workflow)]
        for index, match in enumerate(matches)
    }


def verify() -> list[str]:
    errors: list[str] = []
    build = BUILD_WORKFLOW.read_text(encoding="utf-8")
    production = VERIFY_WORKFLOW.read_text(encoding="utf-8")
    conditions = [line.strip() for line in build.splitlines() if line.strip().startswith("if:")]
    steps = named_steps(build)

    if "push_image:" not in build or "default: false" not in build:
        errors.append("manual image publication is missing its default-false push_image opt-in")
    if conditions.count(PUBLISH_CONDITION) != 2:
        errors.append("image login and publish must require main push or opted-in main dispatch")
    if conditions.count(INFRA_CONDITION) != 3:
        errors.append("all three infra mutation steps must require publication authorization and token")
    for name in ("Log in to GHCR", "Build and push image"):
        if PUBLISH_CONDITION not in steps.get(name, ""):
            errors.append(f"{name} is not guarded by the exact publication policy")
    for name in ("Check out infra repo", "Update infra image tag", "Commit infra image update"):
        if INFRA_CONDITION not in steps.get(name, ""):
            errors.append(f"{name} is not guarded by the exact infra-mutation policy")
    publish_step = steps.get("Build and push image", "")
    if ":latest" not in publish_step or "push: true" not in publish_step:
        errors.append("latest and push:true must stay inside the authorized publish step")
    if any(
        (":latest" in body or "push: true" in body) and name != "Build and push image"
        for name, body in steps.items()
    ):
        errors.append("latest or push:true escaped the authorized publish step")
    if "platforms: linux/amd64\n" not in build or "linux/arm64" in build:
        errors.append("Build Image publish must remain linux/amd64-only pending arm64 proof")
    if "run: ./scripts/smoke_headless_container.sh" not in build:
        errors.append("Build Image is missing its checked-in Headless container smoke")
    if not CONTAINER_SMOKE.is_file():
        errors.append("the Headless container smoke referenced by Build Image is missing")
    elif CONTAINER_SMOKE.stat().st_mode & 0o111 == 0:
        errors.append("the Headless container smoke referenced by Build Image is not executable")
    if 'GAHYEON_HEADLESS_CONTAINER_SKIP_BUILD: "true"' not in build:
        errors.append("container smoke does not target the validation image built by the workflow")

    if "workflows:\n      - Build Image" not in production:
        errors.append("Verify Production no longer follows the Build Image workflow")
    if "github.event.workflow_run.head_branch == 'main'" not in production:
        errors.append("Verify Production workflow_run is not restricted to main")
    if 'expected_image_tag="sha-${DEPLOYED_HEAD_SHA:0:7}"' not in production:
        errors.append("Verify Production does not derive the immutable Build Image SHA tag")
    return errors


def main() -> int:
    errors = verify()
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print("Build Image workflow contract passed: main-only amd64 publish and verification linkage")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
