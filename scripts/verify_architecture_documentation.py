#!/usr/bin/env python3
"""Keep the primary architecture guide aligned with the independent-Agent boundary."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/ARCHITECTURE.md"

REQUIRED = (
    "Discord, Desktop, Headless API와 Unreal Stage",
    "Core와 Application은 JDA",
    "ConversationSession",
    "TranscriptionUseCase",
    "DeterministicBehaviorPolicy",
    "WorldActionPresentationPresence",
    "Reflex",
    "Behavior",
    "Cognition",
    "persist-before-ack",
    "Unreal Engine 5.6",
    "실제 UE 5.6 compile/PIE 증거를 대신하지 않는다",
)

STALE = (
    "Gahyeona Command",
    "OpenAiService.chat()",
    "GPT-4o-mini",
    "시간당 3회",
    "일일 10회",
    "Discord Platform                        │",
)


def verify() -> list[str]:
    errors: list[str] = []
    text = DOC.read_text(encoding="utf-8")
    for marker in REQUIRED:
        if marker not in text:
            errors.append(f"architecture guide is missing current boundary: {marker}")
    for marker in STALE:
        if marker in text:
            errors.append(f"architecture guide contains legacy primary-architecture claim: {marker}")
    for target in re.findall(r"]\(([^)#]+)", text):
        if not (DOC.parent / target).resolve().exists():
            errors.append(f"architecture guide has a missing local link: {target}")
    return errors


def main() -> int:
    errors = verify()
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(f"Architecture documentation contract passed: {len(REQUIRED)} boundaries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
