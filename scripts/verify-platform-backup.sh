#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DUMP="${1:-}"
ENV_FILE="${RELEASE_ENV_FILE:-}"
VERIFY_DB="spaceagent_restore_verify_$(date +%s)_$$"

fail() { echo "FAIL: $*" >&2; exit 1; }
require_bin() { command -v "$1" >/dev/null 2>&1 || fail "$1 is required"; }
for binary in docker jq shasum awk; do require_bin "$binary"; done
[[ -f "$DUMP" ]] || fail "usage: verify-platform-backup.sh <backup.dump>"

compose=(docker compose -f "$ROOT_DIR/docker-compose.yml")
if [[ -n "$ENV_FILE" ]]; then
  [[ -f "$ENV_FILE" ]] || fail "release environment file not found: $ENV_FILE"
  compose=(docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/docker-compose.yml"
    -f "$ROOT_DIR/docker-compose.release.yml")
fi

compose_value() {
  local query="$1"
  local value
  value="$("${compose[@]}" config --format json | jq -er "$query")" \
    || fail "cannot resolve required database configuration from Compose"
  printf '%s' "$value"
}

DB_USER_VALUE="$(compose_value '.services.postgres.environment.POSTGRES_USER | strings | select(length > 0)')"
PLATFORM_DB_VALUE="$(compose_value '.services.postgres.environment.POSTGRES_DB | strings | select(length > 0)')"
EXPECTED_SCHEMA="$(compose_value '.services["platform-server"].environment.PLATFORM_EXPECTED_SCHEMA_VERSION | strings | select(length > 0)')"
[[ "$DB_USER_VALUE" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || fail "invalid database user"
[[ "$PLATFORM_DB_VALUE" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || fail "invalid platform database name"
[[ "$EXPECTED_SCHEMA" == "1098" ]] || fail "unsupported expected schema version: $EXPECTED_SCHEMA"
cleanup() { "${compose[@]}" exec -T postgres dropdb -U "$DB_USER_VALUE" --if-exists "$VERIFY_DB" >/dev/null 2>&1 || true; }
trap cleanup EXIT

manifest="$DUMP.manifest.json"
[[ -f "$manifest" ]] || fail "backup manifest is required"
jq -e --arg database "$PLATFORM_DB_VALUE" --arg schema "$EXPECTED_SCHEMA" '
  .format == "pg_dump-custom" and
  .database == $database and
  .schemaVersion == $schema and
  (.createdAt | type == "string" and test("^[0-9]{8}T[0-9]{6}Z$")) and
  (.sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
  (.releaseVersion | type == "string" and length > 0)
' "$manifest" >/dev/null || fail "backup manifest is invalid or incompatible"
expected="$(jq -er '.sha256' "$manifest")"
actual="$(shasum -a 256 "$DUMP" | awk '{print $1}')"
[[ "$expected" == "$actual" ]] || fail "backup checksum mismatch"
"${compose[@]}" exec -T postgres pg_restore --list < "$DUMP" >/dev/null \
  || fail "backup is not a readable pg_dump custom archive"

"${compose[@]}" exec -T postgres createdb -U "$DB_USER_VALUE" "$VERIFY_DB"
"${compose[@]}" exec -T postgres pg_restore -U "$DB_USER_VALUE" -d "$VERIFY_DB" \
  --no-owner --no-privileges --exit-on-error < "$DUMP"
evidence="$("${compose[@]}" exec -T postgres psql -U "$DB_USER_VALUE" -d "$VERIFY_DB" \
  -X -q -A -t -v ON_ERROR_STOP=1 -F '|' -c \
  "SELECT (SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1),
          to_regclass('platform_users') IS NOT NULL,
          to_regclass('platform_agent_runs') IS NOT NULL,
          to_regclass('platform_project_execution_context_snapshots') IS NOT NULL,
          to_regclass('platform_project_intake_jobs') IS NOT NULL,
          to_regclass('platform_project_coding_jobs') IS NOT NULL,
          to_regclass('platform_project_run_handoffs') IS NOT NULL,
          EXISTS(SELECT 1 FROM information_schema.columns
                  WHERE table_name='platform_model_call_ledger' AND column_name='first_chunk_ms'),
          EXISTS(SELECT 1 FROM information_schema.columns
                  WHERE table_name='platform_tasks' AND column_name='conversation_id'),
          EXISTS(SELECT 1 FROM information_schema.columns
                  WHERE table_name='platform_agent_runs' AND column_name='chat_task_id'),
          EXISTS(SELECT 1 FROM information_schema.columns
                  WHERE table_name='platform_task_plans' AND column_name='conversation_id'),
          EXISTS(SELECT 1 FROM information_schema.columns
                  WHERE table_name='platform_task_plans' AND column_name='source_agent_run_id'),
          to_regclass('platform_source_merge_jobs') IS NOT NULL,
          to_regclass('platform_user_cleanup_jobs') IS NOT NULL,
          to_regclass('platform_user_cleanup_steps') IS NOT NULL,
          to_regclass('platform_knowledge_bases') IS NOT NULL,
          to_regclass('platform_knowledge_generation_chunks') IS NOT NULL,
          to_regclass('platform_knowledge_index_jobs') IS NOT NULL,
          to_regclass('platform_knowledge_index_tombstones') IS NOT NULL,
          to_regclass('platform_knowledge_index_repairs') IS NOT NULL,
          to_regclass('platform_embedding_calls') IS NOT NULL,
          to_regclass('platform_governance_business_attempts') IS NOT NULL,
          to_regclass('platform_governance_business_outcomes') IS NOT NULL,
          EXISTS(SELECT 1 FROM information_schema.columns WHERE table_name='platform_model_call_ledger' AND column_name='usage_actor_id'),
          EXISTS(SELECT 1 FROM information_schema.columns WHERE table_name='platform_tool_execution_ledger' AND column_name='usage_actor_id'),
          to_regclass('ix_artifact_object_content_lookup') IS NOT NULL,
          to_regclass('platform_knowledge_vector_backend') IS NOT NULL,
          to_regclass('platform_knowledge_pgvector_spaces') IS NOT NULL,
          to_regclass('platform_knowledge_pgvector_entries') IS NOT NULL,
          EXISTS(SELECT 1 FROM pg_extension WHERE extname='vector')")"
IFS='|' read -r schema users runs recovery_snapshots intakes coding_jobs handoffs first_chunk chat_tasks chat_runs chat_plans chat_plan_sources merges user_cleanup user_cleanup_steps knowledge_bases knowledge_chunks knowledge_jobs knowledge_tombstones knowledge_repairs embedding_calls business_attempts business_outcomes model_attribution tool_attribution artifact_content_lookup vector_backend vector_spaces vector_entries vector_extension <<< "$evidence"
[[ "$schema" == "$EXPECTED_SCHEMA" \
  && "$users" == "t" && "$runs" == "t" && "$recovery_snapshots" == "t" \
  && "$intakes" == "t" && "$coding_jobs" == "t" && "$handoffs" == "t" \
  && "$first_chunk" == "t" && "$chat_tasks" == "t" && "$chat_runs" == "t" \
  && "$chat_plans" == "t" && "$chat_plan_sources" == "t" \
  && "$merges" == "t" \
  && "$user_cleanup" == "t" && "$user_cleanup_steps" == "t" \
  && "$knowledge_bases" == "t" && "$knowledge_chunks" == "t" && "$knowledge_jobs" == "t" \
  && "$knowledge_tombstones" == "t" && "$knowledge_repairs" == "t" && "$embedding_calls" == "t" \
  && "$business_attempts" == "t" && "$business_outcomes" == "t" && "$model_attribution" == "t" && "$tool_attribution" == "t" \
  && "$artifact_content_lookup" == "t" && "$vector_backend" == "t" \
  && "$vector_spaces" == "t" && "$vector_entries" == "t" && "$vector_extension" == "t" ]] \
  || fail "restored database structural verification failed: $evidence"
echo "Backup restore verification passed in disposable database $VERIFY_DB"
