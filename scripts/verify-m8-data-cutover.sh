#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM_URL=""
M8_PSQL_MODE="${M8_PSQL_MODE:-auto}"
M8_DB_USER="${M8_DB_USERNAME:-${DB_USERNAME:-spaceagent}}"

usage() {
  cat <<'EOF'
Usage:
  verify-m8-data-cutover.sh [--help] [--static]

Environment:
  PLATFORM_DB_URL          required unless --static
  LEGACY_IDENTITY_DB_URL   optional identity count/ID/FK comparison
  LEGACY_AGENT_DB_URL      optional agent count/ID/FK comparison
  LEGACY_CHAT_DB_URL       optional chat count/ID/FK comparison
  LEGACY_KNOWLEDGE_DB_URL  optional knowledge count/ID/FK comparison

Each legacy database is queried only for tables owned by its domain. A supplied
legacy URL enables strict count equality, legacy-ID continuity, and FK checks.
Any mismatch exits non-zero.
EOF
}

normalize_url() {
  local url="$1"
  printf '%s' "${url#jdbc:}"
}

database_name() {
  local url
  url="$(normalize_url "$1")"
  url="${url%%\?*}"
  if [[ "$url" == *"/"* ]]; then
    printf '%s' "${url##*/}"
  else
    printf '%s' "$url"
  fi
}

require_psql() {
  if [[ "$M8_PSQL_MODE" == "auto" ]]; then
    if command -v psql >/dev/null 2>&1; then
      M8_PSQL_MODE="host"
    elif command -v docker >/dev/null 2>&1; then
      M8_PSQL_MODE="docker"
    else
      echo "ERROR: neither host psql nor Docker Compose PostgreSQL is available." >&2
      exit 2
    fi
  fi
  if [[ "$M8_PSQL_MODE" == "host" ]] && ! command -v psql >/dev/null 2>&1; then
    echo "ERROR: host psql mode selected but psql is unavailable." >&2
    exit 2
  fi
  if [[ "$M8_PSQL_MODE" == "docker" ]] && ! command -v docker >/dev/null 2>&1; then
    echo "ERROR: Docker psql mode selected but docker is unavailable." >&2
    exit 2
  fi
}

query() {
  local url="$1"
  local sql="$2"
  if [[ "$M8_PSQL_MODE" == "docker" ]]; then
    docker compose exec -T postgres psql -U "$M8_DB_USER" -d "$(database_name "$url")" \
      -X -q -A -t -v ON_ERROR_STOP=1 -c "$sql"
  else
    psql "$(normalize_url "$url")" -X -q -A -t -v ON_ERROR_STOP=1 -c "$sql"
  fi
}

run_sql_file() {
  local url="$1"
  local file="$2"
  if [[ "$M8_PSQL_MODE" == "docker" ]]; then
    docker compose exec -T postgres psql -U "$M8_DB_USER" -d "$(database_name "$url")" \
      -X -v ON_ERROR_STOP=1 < "$file"
  else
    psql "$(normalize_url "$url")" -X -v ON_ERROR_STOP=1 -f "$file"
  fi
}

static_checks() {
  echo "==> M8 static architecture and Flyway ownership checks"
  "$ROOT_DIR/scripts/check-architecture.sh"

  if [[ -d "$ROOT_DIR/apps/platform-server/src/main/resources/db/migration" ]]; then
    echo "ERROR: platform-server must not own legacy classpath:db/migration." >&2
    exit 1
  fi

  find "$ROOT_DIR/apps/platform-server/src/main/resources/db" \
    -maxdepth 2 -type f -name '*.sql' | sort
}

assert_zero() {
  local label="$1"
  local url="$2"
  local sql="$3"
  local affected
  affected="$(query "$url" "$sql")"
  if [[ "$affected" != "0" ]]; then
    echo "ERROR: $label has $affected foreign-key/invariant violation(s)." >&2
    exit 1
  fi
}

compare_entity() {
  local label="$1"
  local legacy_url="$2"
  local legacy_count_sql="$3"
  local platform_count_sql="$4"
  local legacy_ids_sql="$5"
  local platform_ids_sql="$6"
  local legacy_checksum_sql="$7"
  local platform_checksum_sql="$8"
  local legacy_count platform_count legacy_ids platform_ids missing_ids extra_ids
  local legacy_checksum platform_checksum

  legacy_count="$(query "$legacy_url" "$legacy_count_sql")"
  platform_count="$(query "$PLATFORM_URL" "$platform_count_sql")"
  if [[ "$legacy_count" != "$platform_count" ]]; then
    echo "ERROR: $label count mismatch: legacy=$legacy_count platform=$platform_count" >&2
    exit 1
  fi

  legacy_ids="$(query "$legacy_url" "$legacy_ids_sql")"
  platform_ids="$(query "$PLATFORM_URL" "$platform_ids_sql")"
  missing_ids="$(comm -23 \
    <(printf '%s\n' "$legacy_ids" | sed '/^$/d' | LC_ALL=C sort -u) \
    <(printf '%s\n' "$platform_ids" | sed '/^$/d' | LC_ALL=C sort -u))"
  extra_ids="$(comm -13 \
    <(printf '%s\n' "$legacy_ids" | sed '/^$/d' | LC_ALL=C sort -u) \
    <(printf '%s\n' "$platform_ids" | sed '/^$/d' | LC_ALL=C sort -u))"
  if [[ -n "$missing_ids" || -n "$extra_ids" ]]; then
    echo "ERROR: $label ID continuity mismatch." >&2
    if [[ -n "$missing_ids" ]]; then
      echo "Missing platform IDs:" >&2
      printf '%s\n' "$missing_ids" | head -n 20 >&2
    fi
    if [[ -n "$extra_ids" ]]; then
      echo "Unexpected platform IDs:" >&2
      printf '%s\n' "$extra_ids" | head -n 20 >&2
    fi
    exit 1
  fi
  legacy_checksum="$(query "$legacy_url" "$legacy_checksum_sql")"
  platform_checksum="$(query "$PLATFORM_URL" "$platform_checksum_sql")"
  if [[ "$legacy_checksum" != "$platform_checksum" ]]; then
    echo "ERROR: $label checksum mismatch: legacy=$legacy_checksum platform=$platform_checksum" >&2
    exit 1
  fi
  echo "PASS: $label count=$legacy_count, IDs are continuous, checksum=$legacy_checksum"
}

verify_identity() {
  local url="$1"
  echo "==> Identity legacy/platform verification"
  assert_zero "identity memberships" "$url" \
    "SELECT count(*) FROM tenant_memberships m LEFT JOIN tenants t ON t.id=m.tenant_id LEFT JOIN users u ON u.public_id=m.user_public_id WHERE t.id IS NULL OR u.public_id IS NULL"
  compare_entity "identity.users" "$url" \
    "SELECT count(*) FROM users" \
    "SELECT count(*) FROM platform_users" \
    "SELECT public_id::text FROM users ORDER BY 1" \
    "SELECT id FROM platform_users ORDER BY 1" \
    "SELECT md5(COALESCE(string_agg(public_id::text||':'||username,',' ORDER BY public_id::text),'')) FROM users" \
    "SELECT md5(COALESCE(string_agg(id||':'||external_id,',' ORDER BY id),'')) FROM platform_users"
  compare_entity "identity.tenants" "$url" \
    "SELECT count(*) FROM tenants" \
    "SELECT count(*) FROM platform_tenants" \
    "SELECT id::text FROM tenants ORDER BY 1" \
    "SELECT id FROM platform_tenants ORDER BY 1" \
    "SELECT md5(COALESCE(string_agg(id::text||':'||slug,',' ORDER BY id::text),'')) FROM tenants" \
    "SELECT md5(COALESCE(string_agg(id||':'||slug,',' ORDER BY id),'')) FROM platform_tenants"
  compare_entity "identity.memberships" "$url" \
    "SELECT count(*) FROM tenant_memberships" \
    "SELECT count(*) FROM platform_tenant_memberships" \
    "SELECT tenant_id::text || '|' || user_public_id::text FROM tenant_memberships ORDER BY 1" \
    "SELECT tenant_id || '|' || user_id FROM platform_tenant_memberships ORDER BY 1" \
    "SELECT md5(COALESCE(string_agg(tenant_id::text||':'||user_public_id::text||':'||tenant_role,',' ORDER BY tenant_id::text,user_public_id::text),'')) FROM tenant_memberships" \
    "SELECT md5(COALESCE(string_agg(tenant_id||':'||user_id||':'||tenant_role,',' ORDER BY tenant_id,user_id),'')) FROM platform_tenant_memberships"
}

verify_agent() {
  local url="$1"
  echo "==> Agent legacy/platform verification"
  assert_zero "agent API keys" "$url" \
    "SELECT count(*) FROM agent_api_keys k LEFT JOIN agent_configs a ON a.id=k.agent_id WHERE a.id IS NULL"
  assert_zero "agent Knowledge bindings" "$url" \
    "SELECT count(*) FROM agent_knowledge_bases b LEFT JOIN agent_configs a ON a.id=b.agent_id WHERE a.id IS NULL"
  compare_entity "agent.configurations" "$url" \
    "SELECT count(*) FROM agent_configs" \
    "SELECT count(*) FROM platform_agent_configurations" \
    "SELECT id FROM agent_configs ORDER BY 1" \
    "SELECT id FROM platform_agent_configurations ORDER BY 1" \
    "SELECT md5(COALESCE(string_agg(id||':'||name||':'||config_version,',' ORDER BY id),'')) FROM agent_configs" \
    "SELECT md5(COALESCE(string_agg(id||':'||name||':'||config_version,',' ORDER BY id),'')) FROM platform_agent_configurations"
  compare_entity "agent.api_keys" "$url" \
    "SELECT count(*) FROM agent_api_keys" \
    "SELECT count(*) FROM platform_agent_api_keys" \
    "SELECT id FROM agent_api_keys ORDER BY 1" \
    "SELECT id FROM platform_agent_api_keys ORDER BY 1" \
    "SELECT md5(COALESCE(string_agg(id||':'||agent_id||':'||key_hash,',' ORDER BY id),'')) FROM agent_api_keys" \
    "SELECT md5(COALESCE(string_agg(id||':'||agent_id||':'||key_hash,',' ORDER BY id),'')) FROM platform_agent_api_keys"
}

verify_chat() {
  local url="$1"
  echo "==> Chat legacy/platform verification"
  assert_zero "chat messages" "$url" \
    "SELECT count(*) FROM chat_messages m LEFT JOIN conversations c ON c.public_id=m.conversation_id WHERE c.public_id IS NULL"
  compare_entity "chat.conversations" "$url" \
    "SELECT count(*) FROM conversations" \
    "SELECT count(*) FROM platform_conversations" \
    "SELECT public_id::text FROM conversations ORDER BY 1" \
    "SELECT id FROM platform_conversations ORDER BY 1" \
    "SELECT md5(COALESCE(string_agg(public_id::text||':'||user_id::text||':'||status,',' ORDER BY public_id::text),'')) FROM conversations" \
    "SELECT md5(COALESCE(string_agg(id||':'||user_id||':'||status,',' ORDER BY id),'')) FROM platform_conversations"
  compare_entity "chat.messages" "$url" \
    "SELECT count(*) FROM chat_messages" \
    "SELECT count(*) FROM platform_messages" \
    "SELECT public_id::text FROM chat_messages ORDER BY 1" \
    "SELECT id FROM platform_messages ORDER BY 1" \
    "SELECT md5(COALESCE(string_agg(public_id::text||':'||conversation_id::text||':'||role||':'||md5(content),',' ORDER BY public_id::text),'')) FROM chat_messages" \
    "SELECT md5(COALESCE(string_agg(id||':'||conversation_id||':'||role||':'||md5(content),',' ORDER BY id),'')) FROM platform_messages"
}

verify_knowledge() {
  local url="$1"
  echo "==> Knowledge legacy/platform verification"
  assert_zero "knowledge chunks" "$url" \
    "SELECT count(*) FROM knowledge_chunks c LEFT JOIN knowledge_documents d ON d.id=c.document_id WHERE d.id IS NULL"
  compare_entity "knowledge.documents" "$url" \
    "SELECT count(*) FROM knowledge_documents" \
    "SELECT count(*) FROM platform_knowledge_documents" \
    "SELECT id FROM knowledge_documents ORDER BY 1" \
    "SELECT id FROM platform_knowledge_documents ORDER BY 1" \
    "SELECT md5(COALESCE(string_agg(id||':'||user_id||':'||status,',' ORDER BY id),'')) FROM knowledge_documents" \
    "SELECT md5(COALESCE(string_agg(id||':'||owner_id||':'||status,',' ORDER BY id),'')) FROM platform_knowledge_documents"
  compare_entity "knowledge.chunks" "$url" \
    "SELECT count(*) FROM knowledge_chunks" \
    "SELECT count(*) FROM platform_knowledge_chunks" \
    "SELECT id FROM knowledge_chunks ORDER BY 1" \
    "SELECT id FROM platform_knowledge_chunks ORDER BY 1" \
    "SELECT md5(COALESCE(string_agg(id||':'||document_id||':'||chunk_index||':'||md5(content),',' ORDER BY id),'')) FROM knowledge_chunks" \
    "SELECT md5(COALESCE(string_agg(id||':'||document_id||':'||sequence_number||':'||md5(content),',' ORDER BY id),'')) FROM platform_knowledge_chunks"
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

static_checks

if [[ "${1:-}" == "--static" ]]; then
  echo "M8 static verification passed. Database verification was explicitly skipped."
  exit 0
fi

PLATFORM_URL="${PLATFORM_DB_URL:-}"
if [[ -z "$PLATFORM_URL" ]]; then
  echo "ERROR: PLATFORM_DB_URL is required unless --static is used." >&2
  exit 2
fi
require_psql

echo "==> Strict platform invariants"
run_sql_file "$PLATFORM_URL" "$ROOT_DIR/scripts/m8_migration_checks.sql"

if [[ -n "${LEGACY_IDENTITY_DB_URL:-}" ]]; then
  verify_identity "$LEGACY_IDENTITY_DB_URL"
else
  echo "SKIP: LEGACY_IDENTITY_DB_URL is unset"
fi
if [[ -n "${LEGACY_AGENT_DB_URL:-}" ]]; then
  verify_agent "$LEGACY_AGENT_DB_URL"
else
  echo "SKIP: LEGACY_AGENT_DB_URL is unset"
fi
if [[ -n "${LEGACY_CHAT_DB_URL:-}" ]]; then
  verify_chat "$LEGACY_CHAT_DB_URL"
else
  echo "SKIP: LEGACY_CHAT_DB_URL is unset"
fi
if [[ -n "${LEGACY_KNOWLEDGE_DB_URL:-}" ]]; then
  verify_knowledge "$LEGACY_KNOWLEDGE_DB_URL"
else
  echo "SKIP: LEGACY_KNOWLEDGE_DB_URL is unset"
fi

echo "M8 data cutover verification passed."
