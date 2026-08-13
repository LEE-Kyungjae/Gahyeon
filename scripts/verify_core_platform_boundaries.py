#!/usr/bin/env python3
"""Fail when Core/Application regain platform or persistence dependencies."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


IMPORT = re.compile(r"^\s*import\s+(?:static\s+)?([^;]+);", re.MULTILINE)
PLATFORM_TOKENS = ("net.dv8tion", "discord4j", "org.javacord")
DISCORD_MEDIA_TOKENS = PLATFORM_TOKENS + ("com.sedmelluq.discord",)
PLATFORM_NEUTRAL_INFRA_ROOTS = ("services", "config")
DISCORD_ADAPTER_IMPORT = "com.gahyeonbot.adapters.discord."
DISCORD_ADAPTER_COMPATIBILITY_ROOTS = (
    "com/gahyeonbot/adapters/discord/",
    "com/gahyeonbot/commands/",
    "com/gahyeonbot/listeners/",
)
APPLICATION_FORBIDDEN = (
    "com.gahyeonbot.adapters.",
    "com.gahyeonbot.commands.",
    "com.gahyeonbot.entity.",
    "com.gahyeonbot.listeners.",
    "com.gahyeonbot.repository.",
    "com.gahyeonbot.services.",
)


def inspect(source_root: Path) -> list[dict]:
    violations = []
    for layer in ("core", "application"):
        root = source_root / "com" / "gahyeonbot" / layer
        for path in sorted(root.rglob("*.java")):
            text = path.read_text(encoding="utf-8")
            imports = IMPORT.findall(text)
            for imported in imports:
                reason = None
                if imported.startswith(PLATFORM_TOKENS):
                    reason = "platform_sdk"
                elif layer == "core" and imported.startswith("com.gahyeonbot.") \
                        and not imported.startswith("com.gahyeonbot.core."):
                    reason = "core_outward_dependency"
                elif layer == "application" and imported.startswith(APPLICATION_FORBIDDEN):
                    reason = "application_infrastructure_dependency"
                if reason:
                    violations.append({
                        "file": str(path.relative_to(source_root)),
                        "import": imported,
                        "reason": reason,
                    })
            for token in PLATFORM_TOKENS:
                if token in text and not any(value.startswith(token) for value in imports):
                    violations.append({
                        "file": str(path.relative_to(source_root)),
                        "token": token,
                        "reason": "platform_sdk_fully_qualified",
                    })
    for relative in PLATFORM_NEUTRAL_INFRA_ROOTS:
        root = source_root / "com" / "gahyeonbot" / relative
        for path in sorted(root.rglob("*.java")):
            text = path.read_text(encoding="utf-8")
            imports = IMPORT.findall(text)
            for imported in imports:
                if imported.startswith(DISCORD_ADAPTER_IMPORT):
                    violations.append({
                        "file": str(path.relative_to(source_root)),
                        "import": imported,
                        "reason": "neutral_infrastructure_discord_adapter_dependency",
                    })
            for token in DISCORD_MEDIA_TOKENS:
                if token in text:
                    violations.append({
                        "file": str(path.relative_to(source_root)),
                        "token": token,
                        "reason": "neutral_infrastructure_platform_dependency",
                    })
    project_root = source_root / "com" / "gahyeonbot"
    for path in sorted(project_root.rglob("*.java")):
        relative = str(path.relative_to(source_root))
        if relative.startswith(DISCORD_ADAPTER_COMPATIBILITY_ROOTS):
            continue
        text = path.read_text(encoding="utf-8")
        for token in DISCORD_MEDIA_TOKENS:
            if token in text and not any(
                    item.get("file") == relative
                    and (item.get("token") == token
                         or str(item.get("import", "")).startswith(token))
                    for item in violations):
                violations.append({
                    "file": relative,
                    "token": token,
                    "reason": "discord_sdk_outside_adapter_boundary",
                })
    return violations


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, default=Path("src/main/java"))
    args = parser.parse_args()
    violations = inspect(args.source_root)
    report = {"ready": not violations, "violations": violations}
    print(json.dumps(report, ensure_ascii=False))
    if violations:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
