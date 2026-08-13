#!/usr/bin/env bash
set -Eeuo pipefail

# Disposable-only migration gate. It creates one uniquely named pgvector/PostgreSQL 16
# container, two databases, and application processes launched from the current bootJar.
# It never accepts a remote JDBC URL and removes only the container it created.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
postgres_image="${GAHYEON_POSTGRES_PREFLIGHT_IMAGE:-pgvector/pgvector:0.8.6-pg16-bookworm}"
container_name="${GAHYEON_POSTGRES_PREFLIGHT_CONTAINER_NAME:-gahyeon-postgres-preflight-$$}"
startup_timeout="${GAHYEON_POSTGRES_PREFLIGHT_STARTUP_TIMEOUT:-180}"
skip_build="${GAHYEON_POSTGRES_PREFLIGHT_SKIP_BUILD:-false}"
jar_file="${GAHYEON_POSTGRES_PREFLIGHT_JAR:-$repo_root/build/libs/gahyeonbot-1.0.0.jar}"
report_root="${GAHYEON_POSTGRES_PREFLIGHT_REPORT_ROOT:-$repo_root/build/reports/postgres-migration-preflight}"
run_key="$(date -u +%Y%m%dT%H%M%SZ)-$$"
report_dir="$report_root/$run_key"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/gahyeon-postgres-preflight.XXXXXX")"
fixture_dir="$work_dir/flyway-v24"
empty_db="gahyeon_preflight_empty"
upgrade_db="gahyeon_preflight_upgrade"
app_role="gahyeon_migrator"
admin_password="$(python3 - <<'PY'
import secrets
print(secrets.token_hex(24))
PY
)"
app_password="$(python3 - <<'PY'
import secrets
print(secrets.token_hex(24))
PY
)"
container_created=false
app_pid=""

fail() {
  echo "PostgreSQL migration preflight failed: $*" >&2
  exit 1
}

cleanup() {
  if [[ -n "$app_pid" ]] && kill -0 "$app_pid" 2>/dev/null; then
    kill "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
  fi
  if [[ "$container_created" == true ]]; then
    docker rm -f "$container_name" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$work_dir"
}

on_exit() {
  local status=$?
  if [[ "$status" -ne 0 && "$container_created" == true ]]; then
    docker logs "$container_name" >"$report_dir/postgres-container.log" 2>&1 || true
    echo "Preflight evidence retained at $report_dir" >&2
  fi
  cleanup
  exit "$status"
}
trap on_exit EXIT
trap 'exit 130' INT TERM

for command in docker git java jar python3; do
  command -v "$command" >/dev/null 2>&1 || fail "required command is missing: $command"
done
[[ "$postgres_image" =~ ^pgvector/pgvector:[0-9]+\.[0-9]+\.[0-9]+-pg16-(bookworm|trixie)(@sha256:[0-9a-f]{64})?$ ]] \
  || fail "image must be an explicit pgvector release on PostgreSQL 16: $postgres_image"
[[ "$container_name" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]+$ ]] \
  || fail "invalid container name: $container_name"
[[ "$startup_timeout" =~ ^[0-9]+$ ]] \
  && (( startup_timeout >= 30 && startup_timeout <= 600 )) \
  || fail "startup timeout must be between 30 and 600 seconds"
[[ "$skip_build" == true || "$skip_build" == false ]] \
  || fail "GAHYEON_POSTGRES_PREFLIGHT_SKIP_BUILD must be true or false"

mkdir -p "$report_dir"
cd "$repo_root"

# This fails if the verified commit is unavailable or any applied V1-V24 file changed.
python3 scripts/materialize_flyway_v24_fixture.py --output "$fixture_dir"

if [[ "$skip_build" != true ]]; then
  ./gradlew --no-daemon bootJar --console=plain
fi
[[ -f "$jar_file" ]] || fail "bootJar is missing: $jar_file"
jar tf "$jar_file" >"$report_dir/bootjar-entries.txt"
grep -q 'BOOT-INF/classes/db/migration/V36__Index_agent_run_supersession.sql' \
  "$report_dir/bootjar-entries.txt" || fail "bootJar does not contain V36"
if grep -Eq 'BOOT-INF/classes/db/migration/V3[0-5]__' "$report_dir/bootjar-entries.txt"; then
  fail "bootJar unexpectedly contains a forbidden V30-V35 migration"
fi

if [[ -n "${GAHYEON_POSTGRES_PREFLIGHT_PORT:-}" ]]; then
  postgres_port="$GAHYEON_POSTGRES_PREFLIGHT_PORT"
else
  postgres_port="$(python3 - <<'PY'
import socket
with socket.socket() as value:
    value.bind(("127.0.0.1", 0))
    print(value.getsockname()[1])
PY
)"
fi
[[ "$postgres_port" =~ ^[0-9]+$ ]] \
  && (( postgres_port >= 1024 && postgres_port <= 65535 )) \
  || fail "invalid PostgreSQL port: $postgres_port"

host_postgres_ready() {
  python3 - "$postgres_port" <<'PY'
import socket
import struct
import sys

try:
    with socket.create_connection(("127.0.0.1", int(sys.argv[1])), timeout=2) as connection:
        connection.settimeout(2)
        connection.sendall(struct.pack("!II", 8, 80877103))  # PostgreSQL SSLRequest
        response = connection.recv(1)
        raise SystemExit(0 if response in (b"S", b"N") else 1)
except OSError:
    raise SystemExit(1)
PY
}

docker info >/dev/null 2>&1 || fail "Docker daemon is unavailable"
if docker container inspect "$container_name" >/dev/null 2>&1; then
  fail "refusing to replace existing container: $container_name"
fi

docker run --detach \
  --name "$container_name" \
  --publish "127.0.0.1:$postgres_port:5432" \
  --env POSTGRES_PASSWORD="$admin_password" \
  --env POSTGRES_DB=postgres \
  "$postgres_image" >"$report_dir/container-id.txt"
container_created=true

postgres_ready=false
deadline=$((SECONDS + startup_timeout))
while (( SECONDS < deadline )); do
  # The official image briefly runs an initialization postmaster and then restarts it.
  # Do not mistake that transient server for the final disposable database.
  if docker logs "$container_name" 2>&1 \
      | grep -q 'PostgreSQL init process complete; ready for start up.' \
      && docker exec "$container_name" pg_isready --username postgres --dbname postgres \
        >/dev/null 2>&1 \
      && host_postgres_ready; then
    postgres_ready=true
    break
  fi
  if [[ "$(docker inspect --format '{{.State.Running}}' "$container_name")" != true ]]; then
    fail "disposable PostgreSQL container exited before becoming ready"
  fi
  sleep 1
done
[[ "$postgres_ready" == true ]] || fail "PostgreSQL did not become ready within $startup_timeout seconds"

admin_psql() {
  local database="$1"
  shift
  docker exec -i "$container_name" psql --no-psqlrc --set=ON_ERROR_STOP=1 \
    --username postgres --dbname "$database" "$@"
}

query() {
  local database="$1"
  local sql="$2"
  admin_psql "$database" --tuples-only --no-align --quiet --command "$sql" | tr -d '\r'
}

assert_query() {
  local label="$1"
  local database="$2"
  local expected="$3"
  local sql="$4"
  local actual
  actual="$(query "$database" "$sql")"
  if [[ "$actual" != "$expected" ]]; then
    fail "$label (database=$database, expected='$expected', actual='$actual')"
  fi
}

admin_psql postgres >/dev/null <<SQL
CREATE ROLE $app_role LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '$app_password';
CREATE DATABASE $empty_db OWNER $app_role;
CREATE DATABASE $upgrade_db OWNER $app_role;
SQL

# V7 requires pgvector. A DBA/admin installs it first; the application migration role
# remains non-superuser and V7's CREATE EXTENSION IF NOT EXISTS must be a safe no-op.
for database in "$empty_db" "$upgrade_db"; do
  admin_psql "$database" >/dev/null <<SQL
CREATE EXTENSION vector;
GRANT USAGE, CREATE ON SCHEMA public TO $app_role;
SQL
done
assert_query "migration role must remain non-superuser" postgres "f" \
  "SELECT rolsuper FROM pg_roles WHERE rolname = '$app_role';"
for database in "$empty_db" "$upgrade_db"; do
  assert_query "pgvector prerequisite is missing" "$database" "t" \
    "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector');"
done

run_application() {
  local database="$1"
  local phase="$2"
  local ddl_mode="$3"
  local locations="$4"
  local target="${5:-}"
  local log_file="$report_dir/$phase.log"
  local args=(
    "--spring.profiles.active=prod"
    "--spring.main.web-application-type=none"
    "--spring.datasource.url=jdbc:postgresql://127.0.0.1:$postgres_port/$database"
    "--spring.datasource.username=$app_role"
    "--spring.jpa.hibernate.ddl-auto=$ddl_mode"
    "--spring.flyway.enabled=true"
    "--spring.flyway.locations=$locations"
    "--spring.flyway.baseline-on-migrate=false"
    "--spring.flyway.validate-on-migrate=true"
    "--spring.flyway.clean-disabled=true"
    "--spring.flyway.out-of-order=false"
    "--bot.enabled=false"
    "--weather.prefetch.enabled=false"
    "--notifications.dm.enabled=false"
    "--notifications.dm.trending-enabled=false"
    "--notifications.dm.trending-summary-enabled=false"
    "--gahyeon.headless.enabled=false"
    "--gahyeon.behavior.enabled=false"
    "--gahyeon.unreal.websocket.enabled=false"
    "--gahyeon.agent.provider=none"
    "--gahyeon.content-safety.provider=none"
    "--tts.enabled=false"
    "--assistant.enabled=false"
    "--spring.task.scheduling.enabled=false"
  )
  if [[ -n "$target" ]]; then
    args+=("--spring.flyway.target=$target")
  fi
  SPRING_DATASOURCE_PASSWORD="$app_password" \
    java -jar "$jar_file" "${args[@]}" >"$log_file" 2>&1 &
  app_pid=$!
  local ready=false
  local app_deadline=$((SECONDS + startup_timeout))
  while (( SECONDS < app_deadline )); do
    if grep -Eq 'APPLICATION FAILED TO START| ERROR ' "$log_file"; then
      tail -n 160 "$log_file" >&2
      fail "application emitted an error in phase $phase"
    fi
    if grep -q 'Started Main' "$log_file"; then
      ready=true
      break
    fi
    if ! kill -0 "$app_pid" 2>/dev/null; then
      local exit_code=0
      wait "$app_pid" || exit_code=$?
      app_pid=""
      tail -n 160 "$log_file" >&2
      fail "application exited before startup in phase $phase (exit=$exit_code)"
    fi
    sleep 0.25
  done
  if [[ "$ready" != true ]]; then
    tail -n 160 "$log_file" >&2
    fail "application did not start within $startup_timeout seconds in phase $phase"
  fi

  # ApplicationReady listeners execute immediately after the startup log. Give them a
  # bounded window and fail if the context exits or emits any application error.
  sleep 3
  if grep -Eq 'APPLICATION FAILED TO START| ERROR ' "$log_file"; then
    tail -n 160 "$log_file" >&2
    fail "application emitted an error in phase $phase"
  fi
  if ! kill -0 "$app_pid" 2>/dev/null; then
    local exit_code=0
    wait "$app_pid" || exit_code=$?
    app_pid=""
    [[ "$exit_code" -eq 0 ]] || {
      tail -n 160 "$log_file" >&2
      fail "application exited after startup in phase $phase (exit=$exit_code)"
    }
  else
    kill "$app_pid"
    wait "$app_pid" 2>/dev/null || true
    app_pid=""
  fi
  grep -q 'Initialized JPA EntityManagerFactory' "$log_file" \
    || fail "JPA EntityManagerFactory was not initialized in phase $phase"
}

run_migration_application() {
  local database="$1"
  local phase="$2"
  local locations="$3"
  local target="$4"
  local log_file="$report_dir/$phase.log"
  local args=(
    "--spring.profiles.active=prod"
    "--spring.main.web-application-type=none"
    "--spring.datasource.url=jdbc:postgresql://127.0.0.1:$postgres_port/$database"
    "--spring.datasource.username=$app_role"
    "--spring.jpa.hibernate.ddl-auto=none"
    "--spring.flyway.enabled=true"
    "--spring.flyway.locations=$locations"
    "--spring.flyway.target=$target"
    "--spring.flyway.baseline-on-migrate=false"
    "--spring.flyway.validate-on-migrate=true"
    "--spring.flyway.clean-disabled=true"
    "--spring.flyway.out-of-order=false"
    "--bot.enabled=false"
    "--weather.prefetch.enabled=false"
    "--notifications.dm.enabled=false"
    "--notifications.dm.trending-enabled=false"
    "--notifications.dm.trending-summary-enabled=false"
    "--gahyeon.headless.enabled=false"
    "--gahyeon.behavior.enabled=false"
    "--gahyeon.unreal.websocket.enabled=false"
    "--gahyeon.agent.provider=none"
    "--gahyeon.content-safety.provider=none"
    "--tts.enabled=false"
    "--assistant.enabled=false"
    "--spring.task.scheduling.enabled=false"
  )

  SPRING_DATASOURCE_PASSWORD="$app_password" \
    java -jar "$jar_file" "${args[@]}" >"$log_file" 2>&1 &
  app_pid=$!
  local migrated=false
  local app_deadline=$((SECONDS + startup_timeout))
  while (( SECONDS < app_deadline )); do
    if grep -Eq 'APPLICATION FAILED TO START| ERROR ' "$log_file"; then
      tail -n 160 "$log_file" >&2
      fail "application emitted an error in phase $phase"
    fi
    # Flyway emits this only after the final migration transaction commits. Do not
    # poll through docker exec here: a slow Docker control plane could delay shutdown
    # long enough for application services to start on this intentional staging schema.
    # The assert_history call immediately after this function independently checks
    # the durable catalog state and exact ordered version list.
    if grep -Fq "now at version v$target" "$log_file"; then
      migrated=true
      break
    fi
    if ! kill -0 "$app_pid" 2>/dev/null; then
      local exit_code=0
      wait "$app_pid" || exit_code=$?
      app_pid=""
      tail -n 160 "$log_file" >&2
      fail "application exited before Flyway target $target in phase $phase (exit=$exit_code)"
    fi
    sleep 0.25
  done
  if [[ "$migrated" != true ]]; then
    tail -n 160 "$log_file" >&2
    fail "Flyway did not reach target $target within $startup_timeout seconds in phase $phase"
  fi

  # The current application must never run services against an intentional staging
  # schema. SIGTERM lets Spring finish booting before its shutdown hook runs, which can
  # activate scheduled jobs against the partial schema. Flyway has already committed,
  # so stop this disposable migration-only JVM immediately and verify history below.
  kill -KILL "$app_pid"
  wait "$app_pid" 2>/dev/null || true
  app_pid=""
  if grep -Eq 'APPLICATION FAILED TO START| ERROR ' "$log_file"; then
    tail -n 160 "$log_file" >&2
    fail "application emitted an error in phase $phase"
  fi
}

expected_history() {
  local upper="$1"
  python3 - "$upper" <<'PY'
import sys
upper = int(sys.argv[1])
versions = [str(value) for value in range(1, upper + 1)]
if upper >= 29:
    versions.append("36")
print(",".join(versions))
PY
}

assert_history() {
  local database="$1"
  local upper="$2"
  local expected
  expected="$(expected_history "$upper")"
  assert_query "unexpected successful Flyway history" "$database" "$expected" \
    "SELECT COALESCE(string_agg(version, ',' ORDER BY installed_rank), '') FROM flyway_schema_history WHERE success;"
  assert_query "failed Flyway history row" "$database" "0" \
    "SELECT count(*) FROM flyway_schema_history WHERE NOT success;"
  assert_query "forbidden V30-V35 history row" "$database" "0" \
    "SELECT count(*) FROM flyway_schema_history WHERE version IN ('30','31','32','33','34','35');"
  assert_query "migration was not installed by the restricted role" "$database" "$app_role" \
    "SELECT COALESCE(string_agg(DISTINCT installed_by, ','), '') FROM flyway_schema_history;"
}

assert_current_schema() {
  local database="$1"
  assert_history "$database" 29
  assert_query "V7 vector column is missing" "$database" "t" \
    "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='weather_rag_chunks' AND column_name='embedding' AND udt_name='vector');"
  assert_query "V25 world action table is missing" "$database" "gahyeon_world_actions" \
    "SELECT COALESCE(to_regclass('public.gahyeon_world_actions')::text, '');"
  assert_query "V29 expires_at must be NOT NULL" "$database" "NO" \
    "SELECT is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name='desktop_client_credentials' AND column_name='expires_at';"
  assert_query "V29 expiry index is missing or invalid" "$database" "t" \
    "SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname='idx_desktop_client_credentials_expiry';"
  assert_query "V36 supersession index is missing or invalid" "$database" "t" \
    "SELECT i.indisvalid AND i.indisready FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname='idx_agent_runs_actor_status_created';"
  assert_query "V36 targets the wrong physical columns" "$database" "t" \
    "SELECT position('(user_id, status, created_at)' in pg_get_indexdef(i.indexrelid)) > 0 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname='idx_agent_runs_actor_status_created';"
  assert_query "agent_runs no longer uses the V24 physical columns" "$database" \
    "gateway,guild_id,user_id,username" \
    "SELECT string_agg(column_name, ',' ORDER BY column_name) FROM information_schema.columns WHERE table_schema='public' AND table_name='agent_runs' AND column_name IN ('actor_id','user_id','modality','gateway','tool_scope_id','guild_id','actor_display_name','username');"
  assert_query "openai_usage legacy table is missing" "$database" "openai_usage" \
    "SELECT COALESCE(to_regclass('public.openai_usage')::text, '');"
  assert_query "removed model_usage rename unexpectedly exists" "$database" "t" \
    "SELECT to_regclass('public.model_usage') IS NULL;"
}

# Path A: migrate an empty PostgreSQL 16 database all the way to current and let
# Hibernate validate every current entity mapping against the resulting schema.
run_application "$empty_db" "empty-current" "validate" "classpath:db/migration"
assert_current_schema "$empty_db"

# Path B: create exact V24 schema/history from the verified commit, advance to V28,
# seed rows that V29 must backfill plus agent rows V36 must index, then run current.
run_migration_application "$upgrade_db" "upgrade-v24" "filesystem:$fixture_dir" "24"
assert_history "$upgrade_db" 24
run_migration_application "$upgrade_db" "upgrade-v28" "classpath:db/migration" "28"
assert_history "$upgrade_db" 28

admin_psql "$upgrade_db" >/dev/null <<'SQL'
INSERT INTO gahyeon_principals (id, display_name, created_at, updated_at)
VALUES (4242, 'migration-preflight', TIMESTAMP '2025-01-01 00:00:00', TIMESTAMP '2025-01-01 00:00:00');

INSERT INTO desktop_client_credentials
    (id, credential_hash, principal_id, installation_id, device_label, created_at, last_used_at, revoked_at)
VALUES
    ('preflight-device-000000000000000001', repeat('a', 64), 4242, 'preflight-installation-1',
     'fixture one', TIMESTAMP '2025-01-01 00:00:00', TIMESTAMP '2025-01-02 00:00:00', NULL),
    ('preflight-device-000000000000000002', repeat('b', 64), 4242, 'preflight-installation-2',
     'fixture two', TIMESTAMP '2025-02-01 12:34:56', NULL, NULL);

INSERT INTO agent_sessions (id, session_key, gateway, guild_id, user_id, created_at, updated_at)
VALUES ('preflight-session-00000000000001', 'preflight-session', 'HEADLESS', NULL, 4242,
        TIMESTAMP '2025-01-01 00:00:00', TIMESTAMP '2099-01-01 00:00:00');

INSERT INTO agent_runs
    (id, request_id, session_id, gateway, guild_id, user_id, username, input_text, output_text,
     status, current_step, max_steps, next_event_sequence, created_at, updated_at, version)
VALUES
    ('preflight-run-000000000000000001', 'preflight-request-1', 'preflight-session-00000000000001',
     'HEADLESS', NULL, 4242, 'fixture', 'one', 'done', 'COMPLETED', 1, 4, 1,
     TIMESTAMP '2025-01-01 00:00:00', TIMESTAMP '2099-01-01 00:00:00', 0),
    ('preflight-run-000000000000000002', 'preflight-request-2', 'preflight-session-00000000000001',
     'HEADLESS', NULL, 4242, 'fixture', 'two', 'done', 'COMPLETED', 1, 4, 1,
     TIMESTAMP '2025-01-02 00:00:00', TIMESTAMP '2099-01-01 00:00:00', 0),
    ('preflight-run-000000000000000003', 'preflight-request-3', 'preflight-session-00000000000001',
     'HEADLESS', NULL, 4242, 'fixture', 'three', 'done', 'COMPLETED', 1, 4, 1,
     TIMESTAMP '2025-01-03 00:00:00', TIMESTAMP '2099-01-01 00:00:00', 0);
SQL

run_application "$upgrade_db" "upgrade-current" "validate" "classpath:db/migration"
assert_current_schema "$upgrade_db"
assert_query "V29 did not backfill every legacy credential" "$upgrade_db" "2" \
  "SELECT count(*) FROM desktop_client_credentials WHERE expires_at = created_at + INTERVAL '90 days';"
assert_query "V29 left a NULL expires_at" "$upgrade_db" "0" \
  "SELECT count(*) FROM desktop_client_credentials WHERE expires_at IS NULL;"
assert_query "V36 fixture agent rows were not preserved" "$upgrade_db" "3" \
  "SELECT count(*) FROM agent_runs WHERE user_id=4242;"

resolved_image_id="$(docker inspect --format '{{.Image}}' "$container_name")"
vector_version="$(query "$empty_db" "SELECT extversion FROM pg_extension WHERE extname='vector';")"
{
  printf 'scope=disposable-local-or-ci-only\n'
  printf 'live_production_evidence=false\n'
  printf 'fixture_source_commit=12ebae244fd3efcdbf241dc5215428327552800f\n'
  printf 'postgres_image_requested=%s\n' "$postgres_image"
  printf 'postgres_image_id=%s\n' "$resolved_image_id"
  printf 'pgvector_version=%s\n' "$vector_version"
  printf 'empty_database=V1-V29,V36; application_validate=passed\n'
  printf 'upgrade_database=authoritative_V24_to_V28_seed_to_V29,V36; application_validate=passed\n'
  printf 'v29_backfill_not_null=passed\n'
  printf 'v36_user_id_status_created_index=passed\n'
} >"$report_dir/summary.txt"

echo "Disposable PostgreSQL migration preflight passed"
echo "Evidence: $report_dir/summary.txt"
echo "This is not live-production Flyway evidence."
