# V30–V35 Migration Compatibility Preflight

**Status: SOURCE COMPATIBILITY RESOLVED — deployment is not cleared until the live-history and
disposable-PostgreSQL gates below pass.**

This is a source-level decision, not an assertion about any live database. No production database
or `flyway_schema_history` table was queried while writing it.

## Source decision

The latest verified GitOps image evidence available during this audit was SHA `12ebae2`, whose
repository migrations end at V24. The local V30–V35 rename files were untracked and therefore were
removed before their first normal GitOps release. They must remain absent, and their version
numbers or SQL must not be reused.

[ADR-0011](adr/0011-v24-physical-schema-compatibility.md) records the compatibility decision. Java
keeps neutral domain names while persistence continues to use the V24 physical schema:

| Neutral Java name | V24 physical name |
|---|---|
| `AgentSession.modality`, `AgentRun.modality` | `gateway` |
| `toolScopeId` | `guild_id` |
| `actorId` | `user_id` |
| `AgentRun.actorDisplayName` | `username` |
| `ConversationHistory.actorId` | `conversation_history.user_id` |
| `ModelUsage` | `openai_usage` |
| `ModelUsage.requestId` | `interaction_id` |
| `ModelUsage.actorDisplayName` | `username` |

The identity-merge native SQL likewise updates `user_id` in `conversation_history`,
`agent_sessions`, `agent_runs`, and `openai_usage`. V36 remains as a non-rename migration and its
index targets `agent_runs(user_id, status, created_at)`.

This removes the known source-level mixed-version rename hazard: the previous V24 binary and the
new binary agree on physical names while Blue/Green containers overlap. It does not prove the
state of a persistent database.

## Required live-history gate

Before deployment, use the approved read-only production access path to capture
`flyway_schema_history` for every persistent environment. At minimum, attach the output of an
equivalent query to the release record:

```sql
SELECT installed_rank, version, description, script, checksum, installed_on, success
FROM flyway_schema_history
WHERE version IN ('30', '31', '32', '33', '34', '35', '36')
ORDER BY installed_rank;
```

The expected precondition for this source pivot is that V30–V35 have never been applied. Absence in
Git history or in the current checkout is not proof of that live precondition.

- If V30–V35 are absent in every persistent database, retain the legacy mappings and continue to
  the disposable PostgreSQL gate. Do not recreate or reuse those version numbers.
- If any V30–V35 entry exists, stop this release. Do not edit an applied migration or its checksum.
  Recover the exact applied artifact and design a forward-only recovery migration after the
  highest recorded version, or use a maintenance-window schema/application cutover. A partial
  V30–V35 history is a distinct recovery case.
- If all V30–V35 entries exist in production, the current legacy mappings cannot be assumed to
  match that renamed schema, and the old V24 image is not a safe image-only rollback target. Use a
  forward compatibility migration or an explicitly backed-up downtime cutover.

## Required disposable-PostgreSQL gate

Run the reproducible local/CI gate from the repository root:

```bash
./scripts/preflight_postgres_migrations.sh
```

The script starts only a uniquely named disposable pgvector/PostgreSQL 16 container and refuses to
replace a pre-existing container. It uses a non-superuser migration role after an admin preinstalls
the V7 `vector` extension, forces `baseline-on-migrate=false`, and exercises two independent paths:

1. An empty PostgreSQL database migrates through V29, V36, and V37, after which the current application
   starts with Hibernate `ddl-auto=validate`.
2. The authoritative V24 fixture is extracted byte-for-byte from verified GitOps source commit
   `12ebae244fd3efcdbf241dc5215428327552800f`. Its V1–V24 checksums must still match the checkout.
   The database advances to V28, receives pre-V29 credential rows and pre-V36 agent-run rows, then
   advances to current and starts with application schema validation.

Both paths require exact successful Flyway history `V1–V29,V36,V37`, reject any V30–V35 history,
verify pgvector, the V29 backfill/`NOT NULL`/index, the valid V36
`agent_runs(user_id,status,created_at)` index, the V37 personalized-news table/indexes, and the V24 physical mappings. Evidence is written
under `build/reports/postgres-migration-preflight/`; CI uploads it as a short-lived artifact.

This is disposable evidence, **not live-production evidence**. It does not replace the preceding
read-only `flyway_schema_history` gate. It also proves V29/V36/V37 functional behavior only; it does not
measure lock duration on production-sized tables.

Also verify these operational risks:

- V7 requires `CREATE EXTENSION vector`; pgvector must be installed by the approved privileged
  path before the non-superuser migration role runs.
- The gate proves V29 backfill and `SET NOT NULL` correctness. Separately measure V29 on
  representative `desktop_client_credentials` volume because the backfill and constraint change
  precede a regular index build and can hold locks.
- The gate proves that V36 builds a valid index over populated rows. Separately measure it on
  representative `agent_runs` volume because plain `CREATE INDEX` is transactional but is not a
  zero-write-blocking substitute for an intentionally planned concurrent index build.
- Do not use `baseline-on-migrate` to repair an unknown non-empty schema.

## Image architecture gate

The GitHub image smoke currently uses the runner-native image with the `dev`/H2 profile. Publishing
is restricted to `linux/amd64` because build.gradle packages only libdave
`natives-linux-x86-64`. Do not publish `linux/arm64` until an arm64 native artifact and a
`BOT_ENABLED=true` Discord activation smoke exist.

The source compatibility decision is complete, but a release remains blocked until the observed
live history and disposable-PostgreSQL evidence satisfy these gates.
