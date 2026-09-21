#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DUMP="${1:-}"
TARGET="${RESTORE_TARGET_DATABASE:-}"
CONFIRM="${RESTORE_CONFIRM_DATABASE:-}"
ENV_FILE="${RELEASE_ENV_FILE:-}"

fail() { echo "FAIL: $*" >&2; exit 1; }
require_bin() { command -v "$1" >/dev/null 2>&1 || fail "$1 is required"; }
for binary in docker jq shasum awk; do require_bin "$binary"; done
[[ -f "$DUMP" ]] || fail "usage: restore-platform.sh <backup.dump>"
[[ -n "$TARGET" && "$TARGET" =~ ^[A-Za-z0-9_]+$ ]] || fail "RESTORE_TARGET_DATABASE is required"
[[ "$CONFIRM" == "$TARGET" ]] || fail "RESTORE_CONFIRM_DATABASE must exactly match target"

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
if [[ "$TARGET" == "$PLATFORM_DB_VALUE" ]]; then
  [[ "${ALLOW_IN_PLACE_RESTORE:-}" == "YES" ]] || fail "in-place restore requires ALLOW_IN_PLACE_RESTORE=YES"
  [[ "${RESTORE_PLATFORM_STOPPED:-}" == "YES" ]] || fail "confirm platform is stopped with RESTORE_PLATFORM_STOPPED=YES"
fi

manifest="$DUMP.manifest.json"
[[ -f "$manifest" ]] || fail "backup manifest is required: $manifest"
jq -e --arg database "$PLATFORM_DB_VALUE" --arg schema "$EXPECTED_SCHEMA" '
  .format == "pg_dump-custom" and
  .database == $database and
  .schemaVersion == $schema and
  (.createdAt | type == "string" and test("^[0-9]{8}T[0-9]{6}Z$")) and
  (.sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
  (.releaseVersion | type == "string" and length > 0)
' "$manifest" >/dev/null || fail "backup manifest is invalid or incompatible"
expected="$(jq -er '.sha256' "$manifest")"
manifest_schema="$(jq -er '.schemaVersion' "$manifest")"
actual="$(shasum -a 256 "$DUMP" | awk '{print $1}')"
[[ "$expected" == "$actual" ]] || fail "backup checksum mismatch"
"${compose[@]}" exec -T postgres pg_restore --list < "$DUMP" >/dev/null \
  || fail "backup is not a readable pg_dump custom archive"

"${compose[@]}" exec -T postgres dropdb -U "$DB_USER_VALUE" --if-exists "$TARGET"
"${compose[@]}" exec -T postgres createdb -U "$DB_USER_VALUE" "$TARGET"
"${compose[@]}" exec -T postgres pg_restore -U "$DB_USER_VALUE" -d "$TARGET" \
  --no-owner --no-privileges --exit-on-error < "$DUMP"
schema="$("${compose[@]}" exec -T postgres psql -U "$DB_USER_VALUE" -d "$TARGET" \
  -X -q -A -t -v ON_ERROR_STOP=1 \
  -c "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"
[[ "$schema" == "$manifest_schema" && "$schema" == "$EXPECTED_SCHEMA" ]] \
  || fail "restored schema version $schema does not match manifest/current schema"
echo "Restore completed into explicitly confirmed database: $TARGET"
