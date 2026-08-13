#!/usr/bin/env python3
"""Verify the resolved V24 physical-schema decision and pending release gates."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs/DEPLOYMENT_MIGRATION_BLOCKER.md"
ADR = ROOT / "docs/adr/0011-v24-physical-schema-compatibility.md"
DEPLOYMENT_GUIDE = ROOT / "docs/DEPLOYMENT.md"
IMAGE_WORKFLOW = ROOT / ".github/workflows/build-image.yml"
MIGRATIONS = ROOT / "src/main/resources/db/migration"
V36 = MIGRATIONS / "V36__Index_agent_run_supersession.sql"

SOURCE_FILES = {
    "agent session": ROOT / "src/main/java/com/gahyeonbot/entity/AgentSession.java",
    "agent run": ROOT / "src/main/java/com/gahyeonbot/entity/AgentRun.java",
    "conversation history": ROOT / "src/main/java/com/gahyeonbot/entity/ConversationHistory.java",
    "model usage": ROOT / "src/main/java/com/gahyeonbot/entity/ModelUsage.java",
    "identity merge": ROOT / "src/main/java/com/gahyeonbot/adapters/identity/JpaIdentityLinkAdapter.java",
    "conversation native query": ROOT / "src/main/java/com/gahyeonbot/repository/ConversationHistoryRepository.java",
}

REQUIRED_DOC_MARKERS = (
    "Status: SOURCE COMPATIBILITY RESOLVED",
    "deployment is not cleared",
    "not an assertion about any live database",
    "flyway_schema_history",
    "V30–V35 have never been applied",
    "Do not edit an applied migration or its checksum",
    "disposable PostgreSQL",
    "restricted to `linux/amd64`",
    "Do not publish `linux/arm64`",
    "`BOT_ENABLED=true` Discord activation smoke",
)

REQUIRED_ADR_MARKERS = (
    "AgentSession.modality`, `AgentRun.modality` | `gateway`",
    "`toolScopeId` | `guild_id`",
    "`actorId` | `user_id`",
    "`AgentRun.actorDisplayName` | `username`",
    "`ModelUsage` | `openai_usage`",
    "`ModelUsage.requestId` | `interaction_id`",
    "V30~V35 rename 파일은 제거",
    "flyway_schema_history",
)

REQUIRED_SOURCE_MARKERS = {
    "agent session": ('@Column(name = "gateway"', '@Column(name = "guild_id"', '@Column(name = "user_id"'),
    "agent run": (
        '@Column(name = "gateway"',
        '@Column(name = "guild_id"',
        '@Column(name = "user_id"',
        '@Column(name = "username"',
    ),
    "conversation history": (
        '@Column(name = "user_id"',
        '@Index(name = "idx_conv_user_id", columnList = "user_id")',
        '@Index(name = "idx_conv_user_created", columnList = "user_id, created_at DESC")',
    ),
    "model usage": (
        '@Table(name = "openai_usage"',
        '@Column(name = "interaction_id"',
        '@Column(name = "user_id"',
        '@Column(name = "username"',
        '@Column(name = "guild_id"',
    ),
    "identity merge": (
        'new String[]{"conversation_history"}',
        'new String[]{"agent_sessions", "agent_runs"}',
        '"UPDATE " + table + " SET user_id = :target WHERE user_id = :source"',
        '"UPDATE openai_usage SET user_id = :target WHERE user_id = :source"',
    ),
    "conversation native query": (
        "SELECT c.* FROM conversation_history c",
        "WHERE c.user_id = :actorId",
        "WHERE user_id = :actorId",
    ),
}


def verify() -> list[str]:
    errors: list[str] = []
    if not DOC.is_file():
        return ["deployment migration preflight document is missing"]
    text = DOC.read_text(encoding="utf-8")
    for marker in REQUIRED_DOC_MARKERS:
        if marker not in text:
            errors.append(f"deployment migration preflight is missing marker: {marker}")

    if not ADR.is_file():
        errors.append("V24 physical-schema ADR is missing")
    else:
        adr = ADR.read_text(encoding="utf-8")
        for marker in REQUIRED_ADR_MARKERS:
            if marker not in adr:
                errors.append(f"V24 physical-schema ADR is missing marker: {marker}")

    guide = DEPLOYMENT_GUIDE.read_text(encoding="utf-8")
    if "DEPLOYMENT_MIGRATION_BLOCKER.md" not in guide:
        errors.append("deployment guide does not link the migration preflight")

    workflow = IMAGE_WORKFLOW.read_text(encoding="utf-8")
    if "platforms: linux/amd64\n" not in workflow:
        errors.append("Build Image publish architecture is not restricted to linux/amd64")
    if "linux/arm64" in workflow:
        errors.append("Build Image must not publish arm64 before native activation proof exists")
    if "natives-linux-x86-64" not in workflow or "BOT_ENABLED=true" not in workflow:
        errors.append("Build Image is missing the arm64 native/activation-smoke TODO contract")

    forbidden = []
    for path in MIGRATIONS.glob("V*__*.sql"):
        match = re.match(r"V(\d+)__", path.name)
        if match and 30 <= int(match.group(1)) <= 35:
            forbidden.append(path.name)
    if forbidden:
        errors.append("V30-V35 migration versions must remain absent and unused: " + ", ".join(sorted(forbidden)))

    if not V36.is_file():
        errors.append("V36 supersession index migration is missing")
    else:
        v36 = V36.read_text(encoding="utf-8")
        if "ON agent_runs(user_id, status, created_at)" not in v36:
            errors.append("V36 must target the V24 agent_runs.user_id physical column")
        if "ON agent_runs(actor_id" in v36:
            errors.append("V36 must not target the removed actor_id rename")

    for label, path in SOURCE_FILES.items():
        if not path.is_file():
            errors.append(f"{label} source contract file is missing: {path.name}")
            continue
        source = path.read_text(encoding="utf-8")
        for marker in REQUIRED_SOURCE_MARKERS[label]:
            if marker not in source:
                errors.append(f"{label} no longer preserves V24 physical marker: {marker}")
    return errors


def main() -> int:
    errors = verify()
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print("Deployment migration preflight contract passed: source uses V24 physical schema; live PostgreSQL gates remain pending")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
