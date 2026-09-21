#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${PLATFORM_BASE_URL:-http://127.0.0.1:9000}"
DB_USER="${DB_USERNAME:-spaceagent}"
PLATFORM_DB_NAME="${PLATFORM_DB_NAME:-spaceagent_platform}"
INTERNAL_TOKEN_VALUE="${INTERNAL_SERVICE_TOKEN:-spaceagent-dev-internal-token-change-in-production}"
TMP_DIR="$(mktemp -d)"
SMOKE_SUFFIX="$(date +%s)-$$"
USERNAME="m8-smoke-${SMOKE_SUFFIX}@example.com"
PASSWORD_VALUE="M8SmokePassword123!"
USER_ID=""
TENANT_ID=""
SECOND_USER_ID=""
SECOND_TENANT_ID=""

require_binary() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: $1 is required" >&2
    exit 2
  }
}

require_binary curl
require_binary jq
require_binary docker

cleanup_subject() {
  local smoke_user="$1"
  local smoke_tenant="$2"
  if [[ -n "$smoke_user" ]]; then
    docker compose exec -T postgres psql -U "$DB_USER" -d "$PLATFORM_DB_NAME" \
      -v ON_ERROR_STOP=1 -v smoke_user="$smoke_user" -v smoke_tenant="$smoke_tenant" <<'SQL' >/dev/null
DELETE FROM platform_tool_execution_ledger
WHERE agent_run_id IN (SELECT id FROM platform_agent_runs WHERE owner_id = :'smoke_user');
DELETE FROM platform_run_checkpoints
WHERE agent_run_id IN (SELECT id FROM platform_agent_runs WHERE owner_id = :'smoke_user');
DELETE FROM platform_run_steps
WHERE agent_run_id IN (SELECT id FROM platform_agent_runs WHERE owner_id = :'smoke_user');
DELETE FROM platform_run_recoveries
WHERE agent_run_id IN (SELECT id FROM platform_agent_runs WHERE owner_id = :'smoke_user');
DELETE FROM platform_run_handoffs
WHERE source_agent_run_id IN (SELECT id FROM platform_agent_runs WHERE owner_id = :'smoke_user');
DELETE FROM platform_conversation_context_snapshots
WHERE conversation_id IN (SELECT id FROM platform_conversations WHERE user_id = :'smoke_user');
DELETE FROM platform_agent_runs WHERE owner_id = :'smoke_user';
DELETE FROM platform_messages
WHERE conversation_id IN (SELECT id FROM platform_conversations WHERE user_id = :'smoke_user');
DELETE FROM platform_conversations WHERE user_id = :'smoke_user';
DELETE FROM platform_memory_candidates WHERE scope_id = :'smoke_user';
DELETE FROM platform_consolidated_memories WHERE scope_id = :'smoke_user';
DELETE FROM platform_agent_api_keys
WHERE agent_id IN (SELECT id FROM platform_agent_configurations WHERE owner_id = :'smoke_user');
DELETE FROM platform_agent_knowledge_bindings
WHERE agent_id IN (SELECT id FROM platform_agent_configurations WHERE owner_id = :'smoke_user');
DELETE FROM platform_agent_configurations WHERE owner_id = :'smoke_user';
DELETE FROM platform_agent_definitions WHERE owner_id = :'smoke_user';
DELETE FROM platform_provider_models
WHERE provider_id IN (SELECT id FROM platform_model_providers WHERE owner_id = :'smoke_user');
DELETE FROM platform_model_providers WHERE owner_id = :'smoke_user';
DELETE FROM platform_knowledge_chunks
WHERE document_id IN (SELECT id FROM platform_knowledge_documents WHERE owner_id = :'smoke_user');
DELETE FROM platform_knowledge_documents WHERE owner_id = :'smoke_user';
DELETE FROM platform_access_token_revocations WHERE user_id = :'smoke_user';
DELETE FROM platform_refresh_tokens WHERE user_id = :'smoke_user';
DELETE FROM platform_user_profiles WHERE user_id = :'smoke_user';
DELETE FROM platform_user_credentials WHERE user_id = :'smoke_user';
DELETE FROM platform_tenant_memberships WHERE user_id = :'smoke_user';
DELETE FROM platform_users WHERE id = :'smoke_user';
DELETE FROM platform_tenants WHERE id = :'smoke_tenant';
SQL
  fi
}

cleanup() {
  cleanup_subject "$USER_ID" "$TENANT_ID"
  cleanup_subject "$SECOND_USER_ID" "$SECOND_TENANT_ID"
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

request() {
  local method="$1"
  local path="$2"
  local output="$3"
  local body="${4:-}"
  local token="${5:-}"
  local status
  local args=(-sS -o "$output" -w '%{http_code}' -X "$method" "$BASE_URL$path")
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  if [[ -n "$token" ]]; then
    args+=(-H "Authorization: Bearer $token")
  fi
  status="$(curl "${args[@]}")"
  printf '%s' "$status"
}

assert_status() {
  local actual="$1"
  local expected="$2"
  local label="$3"
  if [[ "$actual" != "$expected" ]]; then
    echo "ERROR: $label expected HTTP $expected, got $actual" >&2
    exit 1
  fi
  echo "PASS: $label HTTP $actual"
}

health_status="$(request GET /actuator/health "$TMP_DIR/health.json")"
assert_status "$health_status" 200 "platform health"
jq -e '.status == "UP"' "$TMP_DIR/health.json" >/dev/null

unauthorized_status="$(request GET /api/v1/agents "$TMP_DIR/unauthorized.json")"
assert_status "$unauthorized_status" 401 "unauthorized access rejection"

register_status="$(request POST /api/v1/auth/register "$TMP_DIR/register.json" \
  "$(jq -nc --arg username "$USERNAME" --arg password "$PASSWORD_VALUE" \
    '{username:$username,password:$password,displayName:"M8 Live Smoke"}')")"
assert_status "$register_status" 200 "identity register"
USER_ID="$(jq -r '.data.userId' "$TMP_DIR/register.json")"
TENANT_ID="$(jq -r '.data.tenantId' "$TMP_DIR/register.json")"
REGISTER_ACCESS_TOKEN="$(jq -r '.data.token' "$TMP_DIR/register.json")"
REGISTER_REFRESH_TOKEN="$(jq -r '.data.refreshToken' "$TMP_DIR/register.json")"

login_status="$(request POST /api/v1/auth/login "$TMP_DIR/login.json" \
  "$(jq -nc --arg username "$USERNAME" --arg password "$PASSWORD_VALUE" \
    '{username:$username,password:$password}')")"
assert_status "$login_status" 200 "identity login"

refresh_status="$(request POST /api/v1/auth/refresh "$TMP_DIR/refresh.json" \
  "$(jq -nc --arg refreshToken "$REGISTER_REFRESH_TOKEN" '{refreshToken:$refreshToken}')")"
assert_status "$refresh_status" 200 "identity refresh"
ACCESS_TOKEN="$(jq -r '.data.token' "$TMP_DIR/refresh.json")"

refresh_replay_status="$(request POST /api/v1/auth/refresh "$TMP_DIR/refresh-replay.json" \
  "$(jq -nc --arg refreshToken "$REGISTER_REFRESH_TOKEN" '{refreshToken:$refreshToken}')")"
assert_status "$refresh_replay_status" 401 "refresh replay rejection"

current_status="$(request GET /api/v1/users/me "$TMP_DIR/current.json" '' "$ACCESS_TOKEN")"
assert_status "$current_status" 200 "identity current-user"
jq -e --arg user_id "$USER_ID" '.data.userId == $user_id' "$TMP_DIR/current.json" >/dev/null

second_register_status="$(request POST /api/v1/auth/register "$TMP_DIR/second-register.json" \
  "$(jq -nc --arg username "second-$USERNAME" --arg password "$PASSWORD_VALUE" \
    '{username:$username,password:$password,displayName:"M9 Isolation User"}')")"
assert_status "$second_register_status" 200 "second identity register"
SECOND_USER_ID="$(jq -r '.data.userId' "$TMP_DIR/second-register.json")"
SECOND_TENANT_ID="$(jq -r '.data.tenantId' "$TMP_DIR/second-register.json")"
SECOND_ACCESS_TOKEN="$(jq -r '.data.token' "$TMP_DIR/second-register.json")"

provider_status="$(request POST /api/v1/model-providers "$TMP_DIR/provider.json" \
  "$(jq -nc '{name:"M8 Smoke Provider",type:"openai",baseUrl:"http://127.0.0.1:1/v1",apiKey:"smoke-provider-key",models:[{modelId:"m8-smoke-model",displayName:"M8 Smoke Model",maxContextTokens:32768,isDefault:true}]}')" \
  "$ACCESS_TOKEN")"
assert_status "$provider_status" 201 "provider create"
PROVIDER_ID="$(jq -r '.data.id' "$TMP_DIR/provider.json")"

document_status="$(request POST /api/v1/knowledge/documents "$TMP_DIR/document.json" \
  "$(jq -nc '{name:"m8-smoke.md",contentType:"text/markdown",storageLocation:"inline:M8 smoke knowledge"}')" \
  "$ACCESS_TOKEN")"
assert_status "$document_status" 200 "knowledge document create"
DOCUMENT_ID="$(jq -r '.data.id' "$TMP_DIR/document.json")"

retrieve_status="$(request POST /api/v1/knowledge/retrieve "$TMP_DIR/retrieve.json" \
  "$(jq -nc --arg document_id "$DOCUMENT_ID" '{documentIds:[$document_id],query:"M8 smoke",topK:3}')" \
  "$ACCESS_TOKEN")"
assert_status "$retrieve_status" 200 "knowledge retrieval boundary"

process_status="$(request POST "/api/v1/knowledge/documents/$DOCUMENT_ID/process" "$TMP_DIR/process.json" '{}' "$ACCESS_TOKEN")"
assert_status "$process_status" 503 "knowledge real embedding configuration error"
jq -e '.code == "KNOWLEDGE_EMBEDDING_NOT_CONFIGURED"' "$TMP_DIR/process.json" >/dev/null

agent_status="$(request POST /api/v1/agents "$TMP_DIR/agent.json" \
  "$(jq -nc --arg provider_id "$PROVIDER_ID" --arg document_id "$DOCUMENT_ID" '{name:"M8 Smoke Agent",systemPrompt:"M8 live smoke",modelProviderId:$provider_id,modelId:"m8-smoke-model",knowledgeBaseIds:[$document_id]}')" \
  "$ACCESS_TOKEN")"
assert_status "$agent_status" 201 "agent create"
AGENT_ID="$(jq -r '.data.id' "$TMP_DIR/agent.json")"

foreign_agent_status="$(request GET "/api/v1/agents/$AGENT_ID" "$TMP_DIR/foreign-agent.json" '' "$SECOND_ACCESS_TOKEN")"
assert_status "$foreign_agent_status" 404 "Agent ownership isolation"

foreign_document_status="$(request GET "/api/v1/knowledge/documents/$DOCUMENT_ID" "$TMP_DIR/foreign-document.json" '' "$SECOND_ACCESS_TOKEN")"
assert_status "$foreign_document_status" 404 "Knowledge ownership isolation"

list_status="$(request GET /api/v1/agents "$TMP_DIR/agents.json" '' "$ACCESS_TOKEN")"
assert_status "$list_status" 200 "agent list"
jq -e --arg agent_id "$AGENT_ID" '.data | any(.id == $agent_id)' "$TMP_DIR/agents.json" >/dev/null

runtime_config_status="$(curl -sS -o "$TMP_DIR/runtime-config.json" -w '%{http_code}' \
  -H "X-Internal-Token: $INTERNAL_TOKEN_VALUE" \
  -H "X-User-Id: $USER_ID" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -H 'X-Tenant-Role: OWNER' \
  "$BASE_URL/internal/agents/$AGENT_ID/runtime-config")"
assert_status "$runtime_config_status" 200 "agent runtime configuration"

key_status="$(request POST "/api/v1/agents/$AGENT_ID/keys" "$TMP_DIR/key.json" \
  "$(jq -nc '{name:"m9-live-key",scopes:["CHAT"]}')" "$ACCESS_TOKEN")"
assert_status "$key_status" 201 "Agent API key create"
RAW_AGENT_KEY="$(jq -r '.data.rawKey' "$TMP_DIR/key.json")"
AGENT_KEY_ID="$(jq -r '.data.apiKey.id' "$TMP_DIR/key.json")"
[[ "$RAW_AGENT_KEY" == agk_* ]]
if grep -q 'keyHash' "$TMP_DIR/key.json"; then
  echo "ERROR: Agent API key response exposed keyHash" >&2
  exit 1
fi

verify_key_status="$(curl -sS -o "$TMP_DIR/key-verify.json" -w '%{http_code}' \
  -H "X-Internal-Token: $INTERNAL_TOKEN_VALUE" \
  -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg rawKey "$RAW_AGENT_KEY" '{rawKey:$rawKey}')" \
  "$BASE_URL/internal/agents/api-keys/verify")"
assert_status "$verify_key_status" 200 "Agent API key verify"
jq -e --arg agent_id "$AGENT_ID" '.data.agentId == $agent_id' "$TMP_DIR/key-verify.json" >/dev/null

revoke_key_status="$(request DELETE "/api/v1/agents/$AGENT_ID/keys/$AGENT_KEY_ID" "$TMP_DIR/key-revoke.json" '' "$ACCESS_TOKEN")"
assert_status "$revoke_key_status" 200 "Agent API key revoke"

revoked_key_status="$(curl -sS -o "$TMP_DIR/key-revoked.json" -w '%{http_code}' \
  -H "X-Internal-Token: $INTERNAL_TOKEN_VALUE" \
  -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg rawKey "$RAW_AGENT_KEY" '{rawKey:$rawKey}')" \
  "$BASE_URL/internal/agents/api-keys/verify")"
assert_status "$revoked_key_status" 401 "revoked Agent API key rejection"

conversation_status="$(request POST /api/v1/chat/conversations "$TMP_DIR/conversation.json" \
  "$(jq -nc --arg agent_id "$AGENT_ID" '{agentId:$agent_id,name:"M8 Smoke Conversation"}')" \
  "$ACCESS_TOKEN")"
assert_status "$conversation_status" 200 "conversation create"
CONVERSATION_ID="$(jq -r '.data.conversationId' "$TMP_DIR/conversation.json")"

chat_status="$(request POST /api/v1/chat/messages "$TMP_DIR/chat.json" \
  "$(jq -nc --arg agent_id "$AGENT_ID" --arg conversation_id "$CONVERSATION_ID" '{agentId:$agent_id,conversationId:$conversation_id,message:"M8 real inference adapter smoke"}')" \
  "$ACCESS_TOKEN")"
assert_status "$chat_status" 502 "chat reaches real inference adapter"
jq -e '.code == "INFERENCE_PROVIDER_UNAVAILABLE"' "$TMP_DIR/chat.json" >/dev/null

set +e
sse_status="$(curl -sS -o "$TMP_DIR/sse.txt" -w '%{http_code}' \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Accept: text/event-stream' \
  -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg agent_id "$AGENT_ID" --arg conversation_id "$CONVERSATION_ID" '{agentId:$agent_id,conversationId:$conversation_id,message:"M8 SSE real adapter smoke"}')" \
  "$BASE_URL/api/v1/chat/messages/stream")"
sse_curl_status=$?
set -e
if [[ "$sse_curl_status" != "0" && "$sse_curl_status" != "18" ]]; then
  echo "ERROR: chat SSE curl failed with exit $sse_curl_status" >&2
  exit 1
fi
assert_status "$sse_status" 200 "chat SSE contract"
grep -q 'event:error' "$TMP_DIR/sse.txt"
grep -q 'INFERENCE_PROVIDER_UNAVAILABLE' "$TMP_DIR/sse.txt"

runtime_evidence="$(docker compose exec -T postgres psql -U "$DB_USER" -d "$PLATFORM_DB_NAME" -Atc \
  "SELECT count(*)||'|'||(SELECT count(*) FROM platform_run_checkpoints c JOIN platform_agent_runs r ON r.id=c.agent_run_id WHERE r.owner_id='$USER_ID' AND c.state_snapshot LIKE '%\"phase\":\"context-compiled\"%')||'|'||(SELECT count(*) FROM platform_messages m JOIN platform_conversations c ON c.id=m.conversation_id WHERE c.user_id='$USER_ID') FROM platform_agent_runs WHERE owner_id='$USER_ID'")"
IFS='|' read -r run_count context_checkpoint_count message_count <<< "$runtime_evidence"
if (( run_count < 2 || context_checkpoint_count < 2 || message_count < 2 )); then
  echo "ERROR: runtime evidence incomplete: runs=$run_count context_checkpoints=$context_checkpoint_count messages=$message_count" >&2
  exit 1
fi
echo "PASS: runtime evidence runs=$run_count context_checkpoints=$context_checkpoint_count messages=$message_count"

echo "Platform live smoke passed; cleanup will restore migrated checksum baseline."
