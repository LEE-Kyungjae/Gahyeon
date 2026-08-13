#!/usr/bin/env python3
"""Fail when the checked-in client API guide drifts from its Java contracts."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs" / "API.md"

CONTRACTS = {
    "src/main/java/com/gahyeonbot/adapters/headless/HeadlessConversationController.java": (
        '@RequestMapping("/gahyeon/conversations")',
        '@PostMapping("/{sessionId}/messages")',
    ),
    "src/main/java/com/gahyeonbot/adapters/headless/HeadlessEventController.java": (
        '@RequestMapping("/gahyeon/events")',
        "@GetMapping",
    ),
    "src/main/java/com/gahyeonbot/adapters/desktop/DesktopConversationController.java": (
        '@RequestMapping("/gahyeon/desktop/conversations")',
        '@PostMapping("/{sessionId}/messages")',
        '@DeleteMapping("/{sessionId}/active")',
    ),
    "src/main/java/com/gahyeonbot/adapters/desktop/DesktopEventStreamController.java": (
        '@RequestMapping("/gahyeon/desktop/events")',
        "@GetMapping",
    ),
    "src/main/java/com/gahyeonbot/adapters/desktop/DesktopSpeechController.java": (
        '@RequestMapping("/gahyeon/desktop/speech")',
        '@GetMapping("/status")',
        '"/transcriptions"',
        '@PostMapping("/segments")',
        '@PostMapping(value = "/synthesis"',
    ),
    "src/main/java/com/gahyeonbot/adapters/desktop/DesktopWorldController.java": (
        '@RequestMapping("/gahyeon/desktop/worlds")',
        '@GetMapping("/{worldId}")',
        '@PostMapping("/{worldId}/move")',
        '@PostMapping("/{worldId}/activity")',
        '@PostMapping("/{worldId}/emotion")',
        '@PostMapping("/{worldId}/actions/{actionId}/complete")',
    ),
    "src/main/java/com/gahyeonbot/adapters/unreal/UnrealSpeechController.java": (
        '@RequestMapping("/gahyeon/unreal/speech")',
        '@GetMapping("/status")',
        '"/transcriptions"',
        '@GetMapping("/audio/{audioId}")',
    ),
}

DOCUMENTED_PATHS = (
    "/api/health",
    "/api/actuator/health",
    "/api/gahyeon/conversations/{sessionId}/messages",
    "/api/gahyeon/events",
    "/api/gahyeon/desktop/conversations/{sessionId}/messages",
    "/api/gahyeon/desktop/conversations/{sessionId}/active",
    "/api/gahyeon/desktop/events",
    "/api/gahyeon/desktop/speech/status",
    "/api/gahyeon/desktop/speech/transcriptions",
    "/api/gahyeon/desktop/speech/segments",
    "/api/gahyeon/desktop/speech/synthesis",
    "/api/gahyeon/desktop/worlds/{worldId}",
    "/api/gahyeon/desktop/worlds/{worldId}/move",
    "/api/gahyeon/desktop/worlds/{worldId}/activity",
    "/api/gahyeon/desktop/worlds/{worldId}/emotion",
    "/api/gahyeon/desktop/worlds/{worldId}/actions/{actionId}/complete",
    "/api/gahyeon/unreal/v1",
    "/api/gahyeon/unreal/speech/status",
    "/api/gahyeon/unreal/speech/transcriptions",
    "/api/gahyeon/unreal/speech/audio/{audioId}",
)

REQUIRED_SECURITY_TERMS = (
    "GAHYEON_CLIENT_TOKEN",
    "Authorization: Bearer",
    "loopback",
    "401 Unauthorized",
    "403 Forbidden",
)

STALE_CLAIMS = (
    "현재 REST API는 인증이 필요하지 않습니다",
    "GPT-4o-mini",
    "시간당 3회",
    "일일 10회",
    "Discord Bot for Reservation & Music",
)

LOCAL_LINKS = (
    "unreal/PROTOCOL_V1.md",
    "contracts/gahyeon-unreal-protocol-v1.schema.json",
)


def verify() -> list[str]:
    errors: list[str] = []
    doc = DOC.read_text(encoding="utf-8")

    for value in DOCUMENTED_PATHS + REQUIRED_SECURITY_TERMS:
        if value not in doc:
            errors.append(f"docs/API.md is missing required contract: {value}")

    for claim in STALE_CLAIMS:
        if claim in doc:
            errors.append(f"docs/API.md contains stale claim: {claim}")

    for source_name, needles in CONTRACTS.items():
        source_path = ROOT / source_name
        if not source_path.is_file():
            errors.append(f"controller source is missing: {source_name}")
            continue
        source = source_path.read_text(encoding="utf-8")
        for needle in needles:
            if needle not in source:
                errors.append(f"{source_name} is missing contract marker: {needle}")

    auth_source = (ROOT / "src/main/java/com/gahyeonbot/adapters/headless/GahyeonClientAuthenticationFilter.java").read_text(encoding="utf-8")
    for needle in ("isLoopback", "SC_UNAUTHORIZED", "SC_FORBIDDEN", "Bearer "):
        if needle not in auth_source:
            errors.append(f"authentication implementation is missing marker: {needle}")

    for relative_link in LOCAL_LINKS:
        if not (DOC.parent / relative_link).is_file():
            errors.append(f"docs/API.md local link target is missing: {relative_link}")

    return errors


def main() -> int:
    errors = verify()
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(
        "API documentation contract passed: "
        f"{len(DOCUMENTED_PATHS)} paths, {len(CONTRACTS)} controllers, "
        f"{len(REQUIRED_SECURITY_TERMS)} security assertions"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
