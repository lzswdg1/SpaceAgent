#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${BACKUP_OUTPUT_DIR:-$ROOT_DIR/.run/backups}"
ENV_FILE="${RELEASE_ENV_FILE:-}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP="$OUTPUT_DIR/spaceagent-platform-$STAMP.dump"
MANIFEST="$DUMP.manifest.json"
TMP_DUMP="$DUMP.tmp"
TMP_MANIFEST="$MANIFEST.tmp"

fail() { echo "FAIL: $*" >&2; exit 1; }
require_bin() { command -v "$1" >/dev/null 2>&1 || fail "$1 is required"; }
for binary in docker jq shasum awk mkdir mv; do require_bin "$binary"; done

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

# Do not source the env file: it is data, not trusted shell code. Compose performs
# interpolation, and jq extracts only the non-secret values this script needs.
DB_USER_VALUE="$(compose_value '.services.postgres.environment.POSTGRES_USER | strings | select(length > 0)')"
DB_NAME_VALUE="$(compose_value '.services.postgres.environment.POSTGRES_DB | strings | select(length > 0)')"
RELEASE_VERSION="$(compose_value '.services["platform-server"].environment.SPACEAGENT_RELEASE_VERSION | strings | select(length > 0)')"
[[ "$DB_USER_VALUE" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || fail "invalid database user"
[[ "$DB_NAME_VALUE" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || fail "invalid database name"

umask 077
mkdir -p "$OUTPUT_DIR"
trap 'rm -f "$TMP_DUMP" "$TMP_MANIFEST"' EXIT
"${compose[@]}" exec -T postgres pg_dump -U "$DB_USER_VALUE" -d "$DB_NAME_VALUE" \
  --format=custom --compress=9 --no-owner --no-privileges > "$TMP_DUMP"
[[ -s "$TMP_DUMP" ]] || fail "pg_dump produced an empty backup"
checksum="$(shasum -a 256 "$TMP_DUMP" | awk '{print $1}')"
schema_version="$("${compose[@]}" exec -T postgres psql -U "$DB_USER_VALUE" \
  -d "$DB_NAME_VALUE" -X -q -A -t -v ON_ERROR_STOP=1 \
  -c "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"
jq -n --arg database "$DB_NAME_VALUE" --arg createdAt "$STAMP" \
  --arg schemaVersion "$schema_version" --arg sha256 "$checksum" \
  --arg releaseVersion "$RELEASE_VERSION" \
  '{format:"pg_dump-custom",database:$database,createdAt:$createdAt,
    schemaVersion:$schemaVersion,sha256:$sha256,releaseVersion:$releaseVersion}' \
  > "$TMP_MANIFEST"
jq -e '
  .format == "pg_dump-custom" and
  (.database | type == "string" and length > 0) and
  (.createdAt | type == "string" and test("^[0-9]{8}T[0-9]{6}Z$")) and
  (.schemaVersion | type == "string" and length > 0) and
  (.sha256 | type == "string" and test("^[0-9a-f]{64}$")) and
  (.releaseVersion | type == "string" and length > 0)
' "$TMP_MANIFEST" >/dev/null || fail "generated backup manifest is invalid"
mv "$TMP_DUMP" "$DUMP"
mv "$TMP_MANIFEST" "$MANIFEST"
trap - EXIT
echo "Backup:   $DUMP"
echo "Manifest: $MANIFEST"
