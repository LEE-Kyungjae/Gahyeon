#!/usr/bin/env python3
"""Keep the disposable PostgreSQL migration gate reproducible and fail closed."""

from __future__ import annotations

from pathlib import Path

import materialize_flyway_v24_fixture as fixture


ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "scripts/preflight_postgres_migrations.sh"
WORKFLOW = ROOT / ".github/workflows/postgres-migration-preflight.yml"
CORE_WORKFLOW = ROOT / ".github/workflows/core-boundaries.yml"
DOC = ROOT / "docs/DEPLOYMENT_MIGRATION_BLOCKER.md"
GUIDE = ROOT / "docs/DEPLOYMENT.md"
MANIFEST = ROOT / "scripts/fixtures/flyway-v24.sha256"

RUNTIME_MARKERS = (
    "pgvector/pgvector:0.8.6-pg16-bookworm",
    "NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION",
    "CREATE EXTENSION vector",
    '"--spring.flyway.baseline-on-migrate=false"',
    '"--spring.flyway.validate-on-migrate=true"',
    '"--spring.flyway.clean-disabled=true"',
    '"--spring.jpa.hibernate.ddl-auto=$ddl_mode"',
    '"filesystem:$fixture_dir" "24"',
    '"classpath:db/migration" "28"',
    'run_migration_application "$upgrade_db" "upgrade-v24"',
    'run_migration_application "$upgrade_db" "upgrade-v28"',
    'grep -Fq "now at version v$target"',
    '"empty-current" "validate"',
    '"upgrade-current" "validate"',
    "APPLICATION FAILED TO START| ERROR ",
    "expires_at = created_at + INTERVAL '90 days'",
    "expires_at IS NULL",
    "idx_desktop_client_credentials_expiry",
    "idx_agent_runs_actor_status_created",
    "(user_id, status, created_at)",
    "V37__Add_personalized_news_articles.sql",
    "V37 personalized news table is missing",
    "v37_personalized_news_schema=passed",
    "V3[0-5]__",
    "live_production_evidence=false",
    'refusing to replace existing container',
    "PostgreSQL init process complete; ready for start up.",
    "PostgreSQL SSLRequest",
)

WORKFLOW_MARKERS = (
    "name: PostgreSQL Migration Preflight",
    "fetch-depth: 0",
    'java-version: "21"',
    "python3 scripts/test_materialize_flyway_v24_fixture.py",
    "python3 scripts/test_verify_postgres_migration_preflight.py",
    "python3 scripts/verify_postgres_migration_preflight.py",
    "./gradlew --no-daemon bootJar",
    "GAHYEON_POSTGRES_PREFLIGHT_SKIP_BUILD: \"true\"",
    "run: ./scripts/preflight_postgres_migrations.sh",
    "actions/upload-artifact@v4",
)

DOC_MARKERS = (
    "preflight_postgres_migrations.sh",
    "authoritative V24",
    "empty PostgreSQL",
    "V29",
    "V36",
    "V37",
    "not live-production",
)


def verify() -> list[str]:
    errors: list[str] = []
    try:
        manifest = fixture.load_manifest(MANIFEST)
        fixture.verify_fixture_sources(manifest)
    except (fixture.FixtureError, OSError) as error:
        errors.append(f"authoritative V24 fixture is invalid: {error}")

    if not RUNTIME.is_file():
        errors.append("disposable PostgreSQL runtime script is missing")
    else:
        runtime = RUNTIME.read_text(encoding="utf-8")
        for marker in RUNTIME_MARKERS:
            if marker not in runtime:
                errors.append(f"PostgreSQL runtime is missing fail-closed marker: {marker}")
        if RUNTIME.stat().st_mode & 0o111 == 0:
            errors.append("disposable PostgreSQL runtime script is not executable")
        if "POSTGRES_PROD_HOST" in runtime or "POSTGRES_PROD_PASSWORD" in runtime:
            errors.append("runtime must not accept production-specific connection variables")
        if "docker compose down" in runtime or "docker system prune" in runtime:
            errors.append("runtime contains an over-broad Docker cleanup command")

    if not WORKFLOW.is_file():
        errors.append("PostgreSQL migration preflight workflow is missing")
    else:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        for marker in WORKFLOW_MARKERS:
            if marker not in workflow:
                errors.append(f"PostgreSQL workflow is missing marker: {marker}")
        for path in (
            '"src/main/resources/db/migration/**"',
            '"src/main/resources/application*.yml"',
            '"src/main/java/com/gahyeonbot/entity/**"',
            '"src/main/java/com/gahyeonbot/repository/**"',
            '"scripts/fixtures/flyway-v24.sha256"',
            '"scripts/preflight_postgres_migrations.sh"',
        ):
            if path not in workflow:
                errors.append(f"PostgreSQL workflow path coverage is missing: {path}")

    core = CORE_WORKFLOW.read_text(encoding="utf-8")
    for command in (
        "python3 scripts/test_materialize_flyway_v24_fixture.py",
        "python3 scripts/test_verify_postgres_migration_preflight.py",
        "python3 scripts/verify_postgres_migration_preflight.py",
    ):
        if command not in core:
            errors.append(f"Core Boundaries does not run source contract: {command}")
    if "fetch-depth: 0" not in core:
        errors.append("Core Boundaries must fetch the authoritative V24 source commit")

    for label, path in (("preflight document", DOC), ("deployment guide", GUIDE)):
        text = path.read_text(encoding="utf-8")
        for marker in DOC_MARKERS:
            if marker not in text:
                errors.append(f"{label} is missing PostgreSQL preflight marker: {marker}")
    return errors


def main() -> int:
    errors = verify()
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print("PostgreSQL migration preflight source contract passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
