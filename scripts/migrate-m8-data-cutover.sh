#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM_DB_NAME="${PLATFORM_DB_NAME:-spaceagent_platform}"
IDENTITY_DB_NAME="${LEGACY_IDENTITY_DB_NAME:-spaceagent_identity}"
AGENT_DB_NAME="${LEGACY_AGENT_DB_NAME:-spaceagent_agent}"
CHAT_DB_NAME="${LEGACY_CHAT_DB_NAME:-spaceagent_chat}"
KNOWLEDGE_DB_NAME="${LEGACY_KNOWLEDGE_DB_NAME:-spaceagent_knowledge}"
DB_USER="${DB_USERNAME:-spaceagent}"
DB_PASSWORD_VALUE="${DB_PASSWORD:-spaceagent123}"
POSTGRES_HOST_PORT_VALUE="${POSTGRES_HOST_PORT:-5436}"
JOB_VERSION="m8-backfill-v1"
EVIDENCE_DIR="$ROOT_DIR/.run/m8"

validate_identifier() {
  local value="$1"
  local label="$2"
  if [[ ! "$value" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
    echo "ERROR: invalid PostgreSQL identifier for $label" >&2
    exit 2
  fi
}

for pair in \
  "$PLATFORM_DB_NAME:platform" \
  "$IDENTITY_DB_NAME:identity" \
  "$AGENT_DB_NAME:agent" \
  "$CHAT_DB_NAME:chat" \
  "$KNOWLEDGE_DB_NAME:knowledge"; do
  validate_identifier "${pair%%:*}" "${pair##*:} database"
done

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: Docker Compose is required for the local M8 migration job." >&2
  exit 2
fi

mkdir -p "$EVIDENCE_DIR"

db_exists="$(docker compose exec -T postgres psql -U "$DB_USER" -d postgres -Atc \
  "SELECT 1 FROM pg_database WHERE datname = '$PLATFORM_DB_NAME'")"
if [[ "$db_exists" != "1" ]]; then
  echo "==> Creating platform database $PLATFORM_DB_NAME"
  docker compose exec -T postgres psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 \
    -c "CREATE DATABASE $PLATFORM_DB_NAME"
else
  echo "==> Platform database $PLATFORM_DB_NAME already exists"
fi

echo "==> Applying platform-server Flyway migrations"
"$ROOT_DIR/mvnw" -q -pl shared/shared-kernel -am install -DskipTests
"$ROOT_DIR/mvnw" -q -pl apps/platform-server flyway:migrate \
  -Dflyway.url="jdbc:postgresql://127.0.0.1:${POSTGRES_HOST_PORT_VALUE}/${PLATFORM_DB_NAME}" \
  -Dflyway.user="$DB_USER" \
  -Dflyway.password="$DB_PASSWORD_VALUE" \
  -Dflyway.locations="filesystem:${ROOT_DIR}/apps/platform-server/src/main/resources/db/platform-server,filesystem:${ROOT_DIR}/apps/platform-server/src/main/resources/db/platform-runtime"

RUN_ID="$(docker compose exec -T postgres psql -U "$DB_USER" -d "$PLATFORM_DB_NAME" -Atc \
  "SELECT gen_random_uuid()::text")"
JOB_CHECKSUM="$(shasum -a 256 "$ROOT_DIR/scripts/m8_backfill.sql" | awk '{print $1}')"
EVIDENCE_FILE="$EVIDENCE_DIR/m8-cutover-${RUN_ID}.log"

exec > >(tee -a "$EVIDENCE_FILE") 2>&1

mark_failed() {
  local exit_code=$?
  docker compose exec -T postgres psql -U "$DB_USER" -d "$PLATFORM_DB_NAME" \
    -v ON_ERROR_STOP=1 -c \
    "UPDATE platform_migration_runs SET status='FAILED', error_message='migration job failed', completed_at=CURRENT_TIMESTAMP WHERE run_id='${RUN_ID}'" \
    >/dev/null 2>&1 || true
  echo "M8 migration failed; evidence: $EVIDENCE_FILE" >&2
  exit "$exit_code"
}
trap mark_failed ERR

echo "==> M8 migration run $RUN_ID"
echo "job_version=$JOB_VERSION"
echo "job_checksum=$JOB_CHECKSUM"

identity_conn="host=127.0.0.1 port=5432 dbname=${IDENTITY_DB_NAME} user=${DB_USER} password=${DB_PASSWORD_VALUE}"
agent_conn="host=127.0.0.1 port=5432 dbname=${AGENT_DB_NAME} user=${DB_USER} password=${DB_PASSWORD_VALUE}"
chat_conn="host=127.0.0.1 port=5432 dbname=${CHAT_DB_NAME} user=${DB_USER} password=${DB_PASSWORD_VALUE}"
knowledge_conn="host=127.0.0.1 port=5432 dbname=${KNOWLEDGE_DB_NAME} user=${DB_USER} password=${DB_PASSWORD_VALUE}"

echo "==> Running idempotent/resumable legacy backfill"
docker compose exec -T postgres psql -U "$DB_USER" -d "$PLATFORM_DB_NAME" \
  -X -v ON_ERROR_STOP=1 \
  -v run_id="$RUN_ID" \
  -v job_version="$JOB_VERSION" \
  -v job_checksum="$JOB_CHECKSUM" \
  -v identity_conn="$identity_conn" \
  -v agent_conn="$agent_conn" \
  -v chat_conn="$chat_conn" \
  -v knowledge_conn="$knowledge_conn" \
  < "$ROOT_DIR/scripts/m8_backfill.sql"

echo "==> Running strict M8 cutover validation"
M8_PSQL_MODE=docker \
M8_DB_USERNAME="$DB_USER" \
PLATFORM_DB_URL="$PLATFORM_DB_NAME" \
LEGACY_IDENTITY_DB_URL="$IDENTITY_DB_NAME" \
LEGACY_AGENT_DB_URL="$AGENT_DB_NAME" \
LEGACY_CHAT_DB_URL="$CHAT_DB_NAME" \
LEGACY_KNOWLEDGE_DB_URL="$KNOWLEDGE_DB_NAME" \
  "$ROOT_DIR/scripts/verify-m8-data-cutover.sh"

docker compose exec -T postgres psql -U "$DB_USER" -d "$PLATFORM_DB_NAME" \
  -v ON_ERROR_STOP=1 -c \
  "UPDATE platform_migration_runs SET status='VERIFIED', completed_at=CURRENT_TIMESTAMP WHERE run_id='${RUN_ID}'"

echo "==> Flyway migration evidence"
docker compose exec -T postgres psql -U "$DB_USER" -d "$PLATFORM_DB_NAME" -P pager=off -c \
  "SELECT installed_rank, version, description, checksum, installed_on, execution_time, success FROM flyway_schema_history ORDER BY installed_rank"

echo "==> Backfill watermark evidence"
docker compose exec -T postgres psql -U "$DB_USER" -d "$PLATFORM_DB_NAME" -P pager=off -c \
  "SELECT domain, last_migrated_id, source_count, target_count, source_checksum, target_checksum, status, completed_at FROM platform_migration_watermarks ORDER BY domain"

echo "==> ID-map evidence"
docker compose exec -T postgres psql -U "$DB_USER" -d "$PLATFORM_DB_NAME" -P pager=off -c \
  "SELECT domain, entity_type, count(*) AS mapped_ids, min(migrated_at) AS first_migrated_at, max(migrated_at) AS last_migrated_at FROM platform_migration_id_map GROUP BY domain, entity_type ORDER BY domain, entity_type"

trap - ERR
echo "M8 migration and validation completed successfully."
echo "evidence=$EVIDENCE_FILE"
