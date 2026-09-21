#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${BASE_URL:-http://127.0.0.1:9000}"
PHASE="${REGRESSION_PHASE:-prepare}"
STATE_DIR="${REGRESSION_STATE_DIR:-$ROOT_DIR/.run/platform-api-regression}"
STATE_FILE="${REGRESSION_STATE_FILE:-$STATE_DIR/state.json}"
DB_USER="${DB_USERNAME:-spaceagent}"
DB_NAME="${PLATFORM_DB_NAME:-spaceagent_platform}"
PROVIDER_BASE_URL="${REGRESSION_PROVIDER_BASE_URL:-http://127.0.0.1:18080/v1}"
PROVIDER_API_KEY="${REGRESSION_PROVIDER_API_KEY:-regression-fixture-key}"
INTERNAL_TOKEN_VALUE="${INTERNAL_SERVICE_TOKEN:-}"
SKIP_DATA_CLEANUP="${REGRESSION_SKIP_DATA_CLEANUP:-0}"
TMP_DIR="$(mktemp -d)"
RUN_SUFFIX="$(date +%s)-$$"
PREFIX="api-regression-$RUN_SUFFIX"
PASSWORD_VALUE="RegressionPassword123!"

USER_ID=""
TENANT_ID=""
SECOND_USER_ID=""
SECOND_TENANT_ID=""
TASK_ID=""
PROJECT_ID=""
CLEANUP_ON_EXIT=0

require_binary() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: $1 is required" >&2
    exit 2
  }
}

for binary in curl jq docker grep; do
  require_binary "$binary"
done

pass() {
  echo "PASS: $*"
}

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

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
  [[ "$actual" == "$expected" ]] || {
    echo "FAIL: $label expected HTTP $expected, got $actual" >&2
    exit 1
  }
  pass "$label HTTP $actual"
}

assert_status_one_of() {
  local actual="$1"
  local expected_csv="$2"
  local label="$3"
  [[ ",$expected_csv," == *",$actual,"* ]] || {
    echo "FAIL: $label expected one of [$expected_csv], got $actual" >&2
    exit 1
  }
  pass "$label HTTP $actual"
}

assert_error_contract() {
  local file="$1"
  local label="$2"
  jq -e '.code != null and .message != null' "$file" >/dev/null \
    || fail "$label missing stable code/message error fields"
  if grep -Eqi 'stacktrace|SQLException|org\.springframework|X-Internal-Token|Bearer [A-Za-z0-9_-]+' "$file"; then
    fail "$label exposed an internal stack, database detail, or credential"
  fi
  pass "$label error contract"
}

db_query() {
  local sql="$1"
  docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" \
    -X -q -A -t -v ON_ERROR_STOP=1 -c "$sql"
}

cleanup_subject() {
  local smoke_user="$1"
  local smoke_tenant="$2"
  local smoke_task="$3"
  local smoke_project="$4"
  [[ -n "$smoke_user" ]] || return 0
  docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 \
    -v smoke_user="$smoke_user" \
    -v smoke_tenant="$smoke_tenant" \
    -v smoke_task="$smoke_task" \
    -v smoke_project="$smoke_project" <<'SQL' >/dev/null
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
DELETE FROM platform_memory_candidates
WHERE scope_id IN (:'smoke_user', :'smoke_task', :'smoke_project')
   OR source_id IN (SELECT id FROM platform_conversations WHERE user_id = :'smoke_user');
DELETE FROM platform_consolidated_memories
WHERE scope_id IN (:'smoke_user', :'smoke_task', :'smoke_project');
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
}

cleanup_all() {
  if [[ "$SKIP_DATA_CLEANUP" == "1" ]]; then
    rm -f "$STATE_FILE"
    return
  fi
  cleanup_subject "$USER_ID" "$TENANT_ID" "$TASK_ID" "$PROJECT_ID"
  cleanup_subject "$SECOND_USER_ID" "$SECOND_TENANT_ID" "" ""
  if [[ -f "$STATE_FILE" ]]; then
    rm -f "$STATE_FILE"
  fi
}

on_exit() {
  local status=$?
  if [[ "$CLEANUP_ON_EXIT" == "1" ]]; then
    cleanup_all || true
  fi
  rm -rf "$TMP_DIR"
  exit "$status"
}
trap on_exit EXIT

load_state() {
  [[ -f "$STATE_FILE" ]] || fail "regression state not found: $STATE_FILE"
  USER_ID="$(jq -r '.userId' "$STATE_FILE")"
  TENANT_ID="$(jq -r '.tenantId' "$STATE_FILE")"
  SECOND_USER_ID="$(jq -r '.secondUserId' "$STATE_FILE")"
  SECOND_TENANT_ID="$(jq -r '.secondTenantId' "$STATE_FILE")"
  TASK_ID="$(jq -r '.taskId' "$STATE_FILE")"
  PROJECT_ID="$(jq -r '.projectId' "$STATE_FILE")"
}

run_prepare() {
  CLEANUP_ON_EXIT=1
  [[ -n "$INTERNAL_TOKEN_VALUE" ]] || fail "INTERNAL_SERVICE_TOKEN is required"

  local health_status root_status auth_config_status branding_status unauthorized_status malformed_status
  health_status="$(request GET /actuator/health "$TMP_DIR/health.json")"
  assert_status "$health_status" 200 "backend health"
  jq -e '.status == "UP"' "$TMP_DIR/health.json" >/dev/null || fail "health status is not UP"
  root_status="$(request GET / "$TMP_DIR/root.json")"
  assert_status "$root_status" 200 "platform root"
  auth_config_status="$(request GET /api/v1/public/auth-config "$TMP_DIR/auth-config.json")"
  assert_status "$auth_config_status" 200 "public auth config"
  branding_status="$(request GET /api/v1/public/branding "$TMP_DIR/branding.json")"
  assert_status "$branding_status" 200 "public branding"
  unauthorized_status="$(request GET /api/v1/agents "$TMP_DIR/unauthorized.json")"
  assert_status "$unauthorized_status" 401 "unauthorized protected endpoint"
  assert_error_contract "$TMP_DIR/unauthorized.json" "unauthorized"
  malformed_status="$(request POST /api/v1/auth/register "$TMP_DIR/malformed.json" '{')"
  assert_status "$malformed_status" 400 "malformed JSON"
  assert_error_contract "$TMP_DIR/malformed.json" "malformed JSON"

  local username_a="$PREFIX-a@example.com"
  local username_b="$PREFIX-b@example.com"
  local register_a_status duplicate_status invalid_login_status login_status refresh_status replay_status
  register_a_status="$(request POST /api/v1/auth/register "$TMP_DIR/register-a.json" \
    "$(jq -nc --arg username "$username_a" --arg password "$PASSWORD_VALUE" \
      '{username:$username,password:$password,displayName:"API Regression A"}')")"
  assert_status "$register_a_status" 200 "identity register A"
  USER_ID="$(jq -r '.data.userId' "$TMP_DIR/register-a.json")"
  TENANT_ID="$(jq -r '.data.tenantId' "$TMP_DIR/register-a.json")"
  local original_refresh="$(jq -r '.data.refreshToken' "$TMP_DIR/register-a.json")"

  duplicate_status="$(request POST /api/v1/auth/register "$TMP_DIR/duplicate-register.json" \
    "$(jq -nc --arg username "$username_a" --arg password "$PASSWORD_VALUE" \
      '{username:$username,password:$password,displayName:"Duplicate"}')")"
  assert_status "$duplicate_status" 409 "duplicate register rejection"
  assert_error_contract "$TMP_DIR/duplicate-register.json" "duplicate register"

  invalid_login_status="$(request POST /api/v1/auth/login "$TMP_DIR/invalid-login.json" \
    "$(jq -nc --arg username "$username_a" '{username:$username,password:"wrong-password"}')")"
  assert_status "$invalid_login_status" 401 "invalid password rejection"
  assert_error_contract "$TMP_DIR/invalid-login.json" "invalid login"

  login_status="$(request POST /api/v1/auth/login "$TMP_DIR/login.json" \
    "$(jq -nc --arg username "$username_a" --arg password "$PASSWORD_VALUE" \
      '{username:$username,password:$password}')")"
  assert_status "$login_status" 200 "identity login"

  refresh_status="$(request POST /api/v1/auth/refresh "$TMP_DIR/refresh.json" \
    "$(jq -nc --arg refreshToken "$original_refresh" '{refreshToken:$refreshToken}')")"
  assert_status "$refresh_status" 200 "refresh rotation"
  local access_token="$(jq -r '.data.token' "$TMP_DIR/refresh.json")"
  local rotated_refresh="$(jq -r '.data.refreshToken' "$TMP_DIR/refresh.json")"
  [[ "$rotated_refresh" != "$original_refresh" ]] || fail "refresh token did not rotate"

  replay_status="$(request POST /api/v1/auth/refresh "$TMP_DIR/replay.json" \
    "$(jq -nc --arg refreshToken "$original_refresh" '{refreshToken:$refreshToken}')")"
  assert_status "$replay_status" 401 "old refresh replay rejection"

  local current_status profile_status update_profile_status register_b_status second_token
  current_status="$(request GET /api/v1/users/me "$TMP_DIR/current.json" '' "$access_token")"
  assert_status "$current_status" 200 "current user"
  jq -e --arg user "$USER_ID" --arg tenant "$TENANT_ID" \
    '.data.userId == $user and .data.tenantId == $tenant and .data.tenantRole == "OWNER"' \
    "$TMP_DIR/current.json" >/dev/null || fail "current user ownership fields mismatch"
  profile_status="$(request GET /api/v1/users/me/profile "$TMP_DIR/profile.json" '' "$access_token")"
  assert_status "$profile_status" 200 "profile get"
  update_profile_status="$(request PUT /api/v1/users/me/profile "$TMP_DIR/profile-update.json" \
    '{"preferredTone":"concise","timezone":"Asia/Shanghai","summary":"regression profile"}' "$access_token")"
  assert_status "$update_profile_status" 200 "profile update"

  register_b_status="$(request POST /api/v1/auth/register "$TMP_DIR/register-b.json" \
    "$(jq -nc --arg username "$username_b" --arg password "$PASSWORD_VALUE" \
      '{username:$username,password:$password,displayName:"API Regression B"}')")"
  assert_status "$register_b_status" 200 "identity register B"
  SECOND_USER_ID="$(jq -r '.data.userId' "$TMP_DIR/register-b.json")"
  SECOND_TENANT_ID="$(jq -r '.data.tenantId' "$TMP_DIR/register-b.json")"
  second_token="$(jq -r '.data.token' "$TMP_DIR/register-b.json")"

  local provider_status provider_id provider_get_status provider_list_status provider_update_status invalid_provider_status
  provider_status="$(request POST /api/v1/model-providers "$TMP_DIR/provider.json" \
    "$(jq -nc --arg name "$PREFIX-provider" --arg base "$PROVIDER_BASE_URL" --arg key "$PROVIDER_API_KEY" \
      '{name:$name,type:"openai-compatible",baseUrl:$base,apiKey:$key,authType:"bearer",enabled:true,isDefault:true,models:[{modelId:"qwen-plus",displayName:"Regression Model",maxContextTokens:32768,isDefault:true}]}')" \
    "$access_token")"
  assert_status "$provider_status" 201 "provider create"
  provider_id="$(jq -r '.data.id' "$TMP_DIR/provider.json")"
  jq -e '.data.apiKey == "configured"' "$TMP_DIR/provider.json" >/dev/null \
    || fail "provider create did not redact secret"
  grep -q "$PROVIDER_API_KEY" "$TMP_DIR/provider.json" && fail "provider secret leaked" || true
  provider_get_status="$(request GET "/api/v1/model-providers/$provider_id" "$TMP_DIR/provider-get.json" '' "$access_token")"
  assert_status "$provider_get_status" 200 "provider get"
  grep -q "$PROVIDER_API_KEY" "$TMP_DIR/provider-get.json" && fail "provider read leaked secret" || true
  provider_list_status="$(request GET /api/v1/model-providers "$TMP_DIR/provider-list.json" '' "$access_token")"
  assert_status "$provider_list_status" 200 "provider list"
  provider_update_status="$(request PUT "/api/v1/model-providers/$provider_id" "$TMP_DIR/provider-update.json" \
    "$(jq -nc --arg name "$PREFIX-provider-updated" --arg base "$PROVIDER_BASE_URL" \
      '{name:$name,type:"openai-compatible",baseUrl:$base,enabled:true,isDefault:true}')" "$access_token")"
  assert_status "$provider_update_status" 200 "provider update"
  invalid_provider_status="$(request POST /api/v1/model-providers "$TMP_DIR/provider-invalid.json" \
    "$(jq -nc --arg name "$PREFIX-invalid-provider" '{name:$name,type:"openai-compatible",baseUrl:"http://metadata.internal/v1",apiKey:"not-secret"}')" \
    "$access_token")"
  assert_status "$invalid_provider_status" 400 "invalid provider endpoint rejection"
  assert_error_contract "$TMP_DIR/provider-invalid.json" "invalid provider"

  local temp_provider_status temp_provider_id add_model_status delete_model_status delete_provider_status
  temp_provider_status="$(request POST /api/v1/model-providers "$TMP_DIR/temp-provider.json" \
    "$(jq -nc --arg name "$PREFIX-temp-provider" --arg base "$PROVIDER_BASE_URL" --arg key "$PROVIDER_API_KEY" \
      '{name:$name,type:"openai-compatible",baseUrl:$base,apiKey:$key,enabled:true,isDefault:false,models:[]}')" "$access_token")"
  assert_status "$temp_provider_status" 201 "temporary provider create"
  temp_provider_id="$(jq -r '.data.id' "$TMP_DIR/temp-provider.json")"
  add_model_status="$(request POST "/api/v1/model-providers/$temp_provider_id/models" "$TMP_DIR/model-add.json" \
    '{"modelId":"temp-model","displayName":"Temp Model","maxContextTokens":4096,"isDefault":true}' "$access_token")"
  assert_status "$add_model_status" 200 "provider model add"
  delete_model_status="$(request DELETE "/api/v1/model-providers/$temp_provider_id/models/temp-model" "$TMP_DIR/model-delete.json" '' "$access_token")"
  assert_status "$delete_model_status" 200 "provider model delete"
  delete_provider_status="$(request DELETE "/api/v1/model-providers/$temp_provider_id" "$TMP_DIR/provider-delete.json" '' "$access_token")"
  assert_status "$delete_provider_status" 200 "provider delete"

  local document_status document_id process_status chunks_status retrieve_status upload_status upload_id delete_upload_status
  document_status="$(request POST /api/v1/knowledge/documents "$TMP_DIR/document.json" \
    "$(jq -nc --arg name "$PREFIX-knowledge.txt" '{name:$name,contentType:"text/plain",storageLocation:"inline:regression"}')" "$access_token")"
  assert_status "$document_status" 200 "knowledge document create"
  document_id="$(jq -r '.data.id' "$TMP_DIR/document.json")"
  process_status="$(request POST "/api/v1/knowledge/documents/$document_id/process" "$TMP_DIR/process.json" \
    '{"content":"SpaceAgent regression knowledge document. The verification code is SA-E2E-2026."}' "$access_token")"
  assert_status "$process_status" 200 "knowledge process with real HTTP embedding adapter"
  jq -e '.data.document.status == "READY" and (.data.chunks | length) > 0 and .data.chunks[0].embeddingDimensions == 1024' \
    "$TMP_DIR/process.json" >/dev/null || fail "knowledge processing metadata mismatch"
  chunks_status="$(request GET "/api/v1/knowledge/documents/$document_id/chunks" "$TMP_DIR/chunks.json" '' "$access_token")"
  assert_status "$chunks_status" 200 "knowledge chunks"
  retrieve_status="$(request POST /api/v1/knowledge/retrieve "$TMP_DIR/retrieve.json" \
    "$(jq -nc --arg id "$document_id" '{documentIds:[$id],query:"SA-E2E-2026",topK:3}')" "$access_token")"
  assert_status "$retrieve_status" 200 "knowledge retrieval"
  jq -e '.data.matches | any(.content | contains("SA-E2E-2026"))' "$TMP_DIR/retrieve.json" >/dev/null \
    || fail "knowledge retrieval did not return verification content"
  upload_status="$(request POST /api/v1/knowledge/documents/upload-reference "$TMP_DIR/upload.json" \
    "$(jq -nc --arg name "$PREFIX-upload.txt" '{name:$name,contentType:"text/plain",storageReference:"object://regression/upload"}')" "$access_token")"
  assert_status "$upload_status" 200 "knowledge upload reference"
  upload_id="$(jq -r '.data.id' "$TMP_DIR/upload.json")"
  delete_upload_status="$(request DELETE "/api/v1/knowledge/documents/$upload_id" "$TMP_DIR/upload-delete.json" '' "$access_token")"
  assert_status "$delete_upload_status" 200 "knowledge document delete"

  local invalid_model_status agent_status agent_id duplicate_agent_status invalid_agent_status agent_get_status agent_list_status agent_update_status
  invalid_model_status="$(request POST /api/v1/agents "$TMP_DIR/agent-invalid-model.json" \
    "$(jq -nc --arg name "$PREFIX-invalid-agent" --arg provider "$provider_id" \
      '{name:$name,modelProviderId:$provider,modelId:"missing-model"}')" "$access_token")"
  assert_status "$invalid_model_status" 400 "invalid provider/model binding rejection"
  agent_status="$(request POST /api/v1/agents "$TMP_DIR/agent.json" \
    "$(jq -nc --arg name "$PREFIX-agent" --arg provider "$provider_id" --arg document "$document_id" \
      '{name:$name,systemPrompt:"Regression system prompt",modelProviderId:$provider,modelId:"qwen-plus",temperature:0.2,maxTokens:1024,maxTurns:10,permissionMode:"private",memoryEnabled:true,ragEnabled:true,networkEnabled:false,knowledgeBaseIds:[$document]}')" "$access_token")"
  assert_status "$agent_status" 201 "Agent create"
  agent_id="$(jq -r '.data.id' "$TMP_DIR/agent.json")"
  duplicate_agent_status="$(request POST /api/v1/agents "$TMP_DIR/agent-duplicate.json" \
    "$(jq -nc --arg name "$PREFIX-agent" '{name:$name}')" "$access_token")"
  assert_status "$duplicate_agent_status" 409 "Agent duplicate name rejection"
  invalid_agent_status="$(request POST /api/v1/agents "$TMP_DIR/agent-invalid.json" '{}' "$access_token")"
  assert_status "$invalid_agent_status" 400 "Agent invalid input"
  agent_get_status="$(request GET "/api/v1/agents/$agent_id" "$TMP_DIR/agent-get.json" '' "$access_token")"
  assert_status "$agent_get_status" 200 "Agent get"
  agent_list_status="$(request GET /api/v1/agents "$TMP_DIR/agent-list.json" '' "$access_token")"
  assert_status "$agent_list_status" 200 "Agent list"
  agent_update_status="$(request PUT "/api/v1/agents/$agent_id" "$TMP_DIR/agent-update.json" \
    "$(jq -nc --arg name "$PREFIX-agent-updated" --arg provider "$provider_id" --arg document "$document_id" \
      '{name:$name,systemPrompt:"Updated regression prompt",modelProviderId:$provider,modelId:"qwen-plus",temperature:0.3,maxTokens:1024,maxTurns:12,permissionMode:"private",memoryEnabled:true,ragEnabled:true,networkEnabled:false,knowledgeBaseIds:[$document]}')" "$access_token")"
  assert_status "$agent_update_status" 200 "Agent update"

  local binding_status runtime_status foreign_agent_status foreign_provider_status foreign_document_status foreign_retrieve_status
  binding_status="$(request GET "/api/v1/agents/$agent_id/knowledge-bindings" "$TMP_DIR/bindings.json" '' "$access_token")"
  assert_status "$binding_status" 200 "Agent Knowledge binding"
  jq -e --arg document "$document_id" '.data | index($document) != null' "$TMP_DIR/bindings.json" >/dev/null \
    || fail "Agent Knowledge binding missing"
  runtime_status="$(curl -sS -o "$TMP_DIR/runtime-config.json" -w '%{http_code}' \
    -H "X-Internal-Token: $INTERNAL_TOKEN_VALUE" -H "X-User-Id: $USER_ID" \
    -H "X-Tenant-Id: $TENANT_ID" -H 'X-Tenant-Role: OWNER' \
    "$BASE_URL/internal/agents/$agent_id/runtime-config")"
  assert_status "$runtime_status" 200 "safe Agent runtime configuration"
  grep -Eqi 'apiKey|ciphertext|secret' "$TMP_DIR/runtime-config.json" && fail "runtime config exposed provider secret" || true
  foreign_agent_status="$(request GET "/api/v1/agents/$agent_id" "$TMP_DIR/foreign-agent.json" '' "$second_token")"
  assert_status "$foreign_agent_status" 404 "cross-tenant Agent isolation"
  foreign_provider_status="$(request GET "/api/v1/model-providers/$provider_id" "$TMP_DIR/foreign-provider.json" '' "$second_token")"
  assert_status "$foreign_provider_status" 404 "cross-tenant Provider isolation"
  foreign_document_status="$(request GET "/api/v1/knowledge/documents/$document_id" "$TMP_DIR/foreign-document.json" '' "$second_token")"
  assert_status "$foreign_document_status" 404 "cross-tenant Knowledge isolation"
  foreign_retrieve_status="$(request POST /api/v1/knowledge/retrieve "$TMP_DIR/foreign-retrieve.json" \
    "$(jq -nc --arg id "$document_id" '{documentIds:[$id],query:"SA-E2E-2026",topK:3}')" "$second_token")"
  assert_status "$foreign_retrieve_status" 404 "cross-tenant Knowledge retrieval isolation"

  local temp_agent_status temp_agent_id delete_agent_status
  temp_agent_status="$(request POST /api/v1/agents "$TMP_DIR/temp-agent.json" \
    "$(jq -nc --arg name "$PREFIX-temp-agent" --arg provider "$provider_id" \
      '{name:$name,modelProviderId:$provider,modelId:"qwen-plus"}')" "$access_token")"
  assert_status "$temp_agent_status" 201 "temporary Agent create"
  temp_agent_id="$(jq -r '.data.id' "$TMP_DIR/temp-agent.json")"
  delete_agent_status="$(request DELETE "/api/v1/agents/$temp_agent_id" "$TMP_DIR/temp-agent-delete.json" '' "$access_token")"
  assert_status "$delete_agent_status" 200 "Agent delete"

  local key_status raw_key key_id key_list_status verify_key_status revoke_key_status revoked_key_status
  key_status="$(request POST "/api/v1/agents/$agent_id/keys" "$TMP_DIR/key.json" \
    "$(jq -nc --arg name "$PREFIX-key" '{name:$name,scopes:["CHAT"]}')" "$access_token")"
  assert_status "$key_status" 201 "Agent API key create"
  raw_key="$(jq -r '.data.rawKey' "$TMP_DIR/key.json")"
  key_id="$(jq -r '.data.apiKey.id' "$TMP_DIR/key.json")"
  [[ "$raw_key" == agk_* ]] || fail "Agent raw key prefix missing"
  grep -q 'keyHash' "$TMP_DIR/key.json" && fail "Agent key hash exposed" || true
  key_list_status="$(request GET "/api/v1/agents/$agent_id/keys" "$TMP_DIR/key-list.json" '' "$access_token")"
  assert_status "$key_list_status" 200 "Agent API key list"
  grep -q "$raw_key" "$TMP_DIR/key-list.json" && fail "Agent key list exposed raw key" || true
  verify_key_status="$(curl -sS -o "$TMP_DIR/key-verify.json" -w '%{http_code}' \
    -H "X-Internal-Token: $INTERNAL_TOKEN_VALUE" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg rawKey "$raw_key" '{rawKey:$rawKey}')" \
    "$BASE_URL/internal/agents/api-keys/verify")"
  assert_status "$verify_key_status" 200 "Agent API key verify"
  revoke_key_status="$(request DELETE "/api/v1/agents/$agent_id/keys/$key_id" "$TMP_DIR/key-revoke.json" '' "$access_token")"
  assert_status "$revoke_key_status" 200 "Agent API key revoke"
  revoked_key_status="$(curl -sS -o "$TMP_DIR/key-revoked.json" -w '%{http_code}' \
    -H "X-Internal-Token: $INTERNAL_TOKEN_VALUE" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg rawKey "$raw_key" '{rawKey:$rawKey}')" \
    "$BASE_URL/internal/agents/api-keys/verify")"
  assert_status "$revoked_key_status" 401 "revoked Agent API key rejection"
  [[ "$(db_query "SELECT count(*) FROM platform_agent_api_keys WHERE id='$key_id' AND key_hash='$raw_key'")" == "0" ]] \
    || fail "Agent API key persisted plaintext"

  local conversation_status conversation_id list_conversation_status get_conversation_status foreign_conversation_status
  conversation_status="$(request POST /api/v1/chat/conversations "$TMP_DIR/conversation.json" \
    "$(jq -nc --arg agent "$agent_id" --arg name "$PREFIX-conversation" '{agentId:$agent,name:$name}')" "$access_token")"
  assert_status "$conversation_status" 200 "Conversation create"
  conversation_id="$(jq -r '.data.conversationId' "$TMP_DIR/conversation.json")"
  list_conversation_status="$(request GET '/api/v1/chat/conversations?page=1&size=20' "$TMP_DIR/conversation-list.json" '' "$access_token")"
  assert_status "$list_conversation_status" 200 "Conversation list"
  get_conversation_status="$(request GET "/api/v1/chat/conversations/$conversation_id" "$TMP_DIR/conversation-get.json" '' "$access_token")"
  assert_status "$get_conversation_status" 200 "Conversation get"
  foreign_conversation_status="$(request GET "/api/v1/chat/conversations/$conversation_id" "$TMP_DIR/foreign-conversation.json" '' "$second_token")"
  assert_status "$foreign_conversation_status" 404 "cross-tenant Conversation isolation"

  local temp_conversation_status temp_conversation_id delete_conversation_status deleted_conversation_status
  temp_conversation_status="$(request POST /api/v1/chat/conversations "$TMP_DIR/temp-conversation.json" \
    "$(jq -nc --arg agent "$agent_id" '{agentId:$agent,name:"temporary conversation"}')" "$access_token")"
  assert_status "$temp_conversation_status" 200 "temporary Conversation create"
  temp_conversation_id="$(jq -r '.data.conversationId' "$TMP_DIR/temp-conversation.json")"
  delete_conversation_status="$(request DELETE "/api/v1/chat/conversations/$temp_conversation_id" "$TMP_DIR/temp-conversation-delete.json" '' "$access_token")"
  assert_status "$delete_conversation_status" 200 "Conversation delete"
  deleted_conversation_status="$(request GET "/api/v1/chat/conversations/$temp_conversation_id" "$TMP_DIR/temp-conversation-missing.json" '' "$access_token")"
  assert_status "$deleted_conversation_status" 404 "deleted Conversation rejection"

  local normal_chat_status preference_chat_status tool_chat_status agent_run_id preference_run_id tool_run_id
  normal_chat_status="$(request POST /api/v1/chat/messages "$TMP_DIR/chat-normal.json" \
    "$(jq -nc --arg agent "$agent_id" --arg conversation "$conversation_id" \
      '{agentId:$agent,conversationId:$conversation,message:"Hello from the backend regression"}')" "$access_token")"
  assert_status "$normal_chat_status" 200 "normal Chat"
  jq -e '.data.assistantMessage == "SpaceAgent E2E reply" and .data.memoryUpdated == false' \
    "$TMP_DIR/chat-normal.json" >/dev/null || fail "normal Chat response mismatch"
  agent_run_id="$(jq -r '.data.agentRunId' "$TMP_DIR/chat-normal.json")"
  [[ "$(db_query "SELECT count(*) FROM platform_memory_candidates WHERE source_id='$conversation_id'")" == "0" ]] \
    || fail "ordinary chat created durable memory"

  preference_chat_status="$(request POST /api/v1/chat/messages "$TMP_DIR/chat-preference.json" \
    "$(jq -nc --arg agent "$agent_id" --arg conversation "$conversation_id" \
      '{agentId:$agent,conversationId:$conversation,message:"My preference is to use Java for backend development."}')" "$access_token")"
  assert_status "$preference_chat_status" 200 "preference Chat"
  jq -e '.data.memoryUpdated == true' "$TMP_DIR/chat-preference.json" >/dev/null \
    || fail "preference message did not create MemoryCandidate"
  preference_run_id="$(jq -r '.data.agentRunId' "$TMP_DIR/chat-preference.json")"
  local candidate_id="$(db_query "SELECT id FROM platform_memory_candidates WHERE scope_id='$USER_ID' AND source_id='$conversation_id' ORDER BY created_at DESC LIMIT 1")"
  [[ -n "$candidate_id" ]] || fail "preference MemoryCandidate not persisted"

  tool_chat_status="$(request POST /api/v1/chat/messages "$TMP_DIR/chat-tool.json" \
    "$(jq -nc --arg agent "$agent_id" --arg conversation "$conversation_id" \
      '{agentId:$agent,conversationId:$conversation,message:"tool:echo SA-TOOL-E2E-2026"}')" "$access_token")"
  assert_status "$tool_chat_status" 200 "tool Chat through Runtime"
  if ! jq -e '.data.assistantMessage | contains("SA-TOOL-E2E-2026")' "$TMP_DIR/chat-tool.json" >/dev/null; then
    jq '{assistantMessage:.data.assistantMessage,events:.data.events}' "$TMP_DIR/chat-tool.json" >&2
    fail "tool result missing from Chat response"
  fi
  jq -e '.data.events | (index("tool_call") != null and index("tool_result") != null)' "$TMP_DIR/chat-tool.json" >/dev/null \
    || fail "tool_call/tool_result runtime events missing"
  tool_run_id="$(jq -r '.data.agentRunId' "$TMP_DIR/chat-tool.json")"
  [[ "$(db_query "SELECT count(*) FROM platform_tool_execution_ledger WHERE agent_run_id='$tool_run_id' AND tool_call_id='call-regression-echo' AND status='SUCCEEDED'")" == "1" ]] \
    || fail "ToolExecutionLedger record missing"

  local replay_chat_status replay_run_id conflict_chat_status conflict_run_id
  replay_chat_status="$(request POST /api/v1/chat/messages "$TMP_DIR/chat-tool-replay.json" \
    "$(jq -nc --arg agent "$agent_id" --arg conversation "$conversation_id" \
      '{agentId:$agent,conversationId:$conversation,message:"tool:echo-replay SA-TOOL-REPLAY-2026"}')" "$access_token")"
  assert_status "$replay_chat_status" 200 "ToolExecutionLedger replay Chat"
  jq -e '
      ([.data.events[] | select(. == "tool_call")] | length) == 2 and
      ([.data.events[] | select(. == "tool_result")] | length) == 2 and
      ([.data.assistantMessage | scan("SA-TOOL-REPLAY-2026")] | length) == 2
    ' "$TMP_DIR/chat-tool-replay.json" >/dev/null \
    || fail "same runId/toolCallId replay response mismatch"
  replay_run_id="$(jq -r '.data.agentRunId' "$TMP_DIR/chat-tool-replay.json")"
  [[ "$(db_query "SELECT count(*) FROM platform_tool_execution_ledger WHERE agent_run_id='$replay_run_id' AND tool_call_id='call-regression-replay' AND status='SUCCEEDED'")" == "1" ]] \
    || fail "same runId/toolCallId replay created duplicate durable side effects"
  pass "ToolExecutionLedger replay reused one durable side effect"

  conflict_chat_status="$(request POST /api/v1/chat/messages "$TMP_DIR/chat-tool-conflict.json" \
    "$(jq -nc --arg agent "$agent_id" --arg conversation "$conversation_id" \
      '{agentId:$agent,conversationId:$conversation,message:"tool:echo-conflict SA-TOOL-CONFLICT-2026"}')" "$access_token")"
  assert_status "$conflict_chat_status" 409 "ToolExecutionLedger inputHash conflict"
  assert_error_contract "$TMP_DIR/chat-tool-conflict.json" "ToolExecutionLedger inputHash conflict"
  jq -e '.code == "TOOL_IDEMPOTENCY_CONFLICT"' "$TMP_DIR/chat-tool-conflict.json" >/dev/null \
    || fail "ToolExecutionLedger inputHash conflict code mismatch"
  conflict_run_id="$(db_query "SELECT l.agent_run_id FROM platform_tool_execution_ledger l JOIN platform_agent_runs r ON r.id=l.agent_run_id WHERE r.owner_id='$USER_ID' AND l.tool_call_id='call-regression-conflict' ORDER BY l.started_at DESC LIMIT 1")"
  [[ -n "$conflict_run_id" ]] || fail "ToolExecutionLedger conflict run missing"
  [[ "$(db_query "SELECT count(*) FROM platform_tool_execution_ledger WHERE agent_run_id='$conflict_run_id' AND tool_call_id='call-regression-conflict' AND status='SUCCEEDED'")" == "1" ]] \
    || fail "ToolExecutionLedger conflict caused a duplicate side effect"
  [[ "$(db_query "SELECT count(*) FROM platform_agent_runs WHERE id='$conflict_run_id' AND state='FAILED'")" == "1" ]] \
    || fail "ToolExecutionLedger conflict run was not durably failed"
  pass "ToolExecutionLedger inputHash conflict prevented duplicate side effect"

  local sse_status sse_content_type delta_count done_count error_count
  sse_status="$(curl -sS -D "$TMP_DIR/sse.headers" -o "$TMP_DIR/sse.txt" -w '%{http_code}' \
    -H "Authorization: Bearer $access_token" -H 'Accept: text/event-stream' \
    -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg agent "$agent_id" --arg conversation "$conversation_id" \
      '{agentId:$agent,conversationId:$conversation,message:"SSE regression message"}')" \
    "$BASE_URL/api/v1/chat/messages/stream")"
  assert_status "$sse_status" 200 "SSE stream"
  sse_content_type="$(grep -i '^Content-Type:' "$TMP_DIR/sse.headers" | tr -d '\r' | tail -1)"
  [[ "$sse_content_type" == *"text/event-stream"* ]] || fail "SSE Content-Type mismatch"
  delta_count="$(grep -c '^event:delta' "$TMP_DIR/sse.txt" || true)"
  done_count="$(grep -c '^event:done' "$TMP_DIR/sse.txt" || true)"
  error_count="$(grep -c '^event:error' "$TMP_DIR/sse.txt" || true)"
  (( delta_count >= 1 )) || fail "SSE emitted no delta event"
  [[ "$done_count" == "1" ]] || fail "SSE terminal done count is $done_count"
  [[ "$error_count" == "0" ]] || fail "SSE emitted an unexpected error event"
  pass "SSE delta/done contract and unique terminal event"

  local history_status
  history_status="$(request GET "/api/v1/chat/conversations/$conversation_id/messages" "$TMP_DIR/history.json" '' "$access_token")"
  assert_status "$history_status" 200 "Conversation message history"
  jq -e '(.data | length) >= 8 and .data[0].role == "USER"' "$TMP_DIR/history.json" >/dev/null \
    || fail "Conversation history missing persisted messages"
  [[ "$(db_query "SELECT count(*) FROM (SELECT sequence_number FROM platform_messages WHERE conversation_id='$conversation_id' GROUP BY sequence_number HAVING count(*) > 1) duplicate")" == "0" ]] \
    || fail "Conversation message sequence is not unique"

  local candidate_status foreign_candidate_status foreign_recall_status review_status
  candidate_status="$(request GET "/api/v1/memory/candidates/$candidate_id" "$TMP_DIR/candidate.json" '' "$access_token")"
  assert_status "$candidate_status" 200 "MemoryCandidate get"
  foreign_candidate_status="$(request GET "/api/v1/memory/candidates/$candidate_id" "$TMP_DIR/foreign-candidate.json" '' "$second_token")"
  assert_status_one_of "$foreign_candidate_status" "403,404" "cross-tenant MemoryCandidate isolation"
  foreign_recall_status="$(request GET "/api/v1/memory?scope=USER&scopeId=$USER_ID&limit=50" "$TMP_DIR/foreign-memory.json" '' "$second_token")"
  assert_status_one_of "$foreign_recall_status" "403,404" "cross-tenant Memory recall isolation"
  review_status="$(request POST "/api/v1/memory/candidates/$candidate_id/review" "$TMP_DIR/candidate-review.json" \
    '{"decision":"ACCEPTED","reason":"regression review"}' "$access_token")"
  assert_status "$review_status" 200 "MemoryCandidate review"

  TASK_ID="$PREFIX-task"
  PROJECT_ID="$PREFIX-project"
  local foreign_user_propose_status foreign_project_propose_status foreign_task_propose_status foreign_consolidate_status
  foreign_user_propose_status="$(request POST /api/v1/memory/candidates "$TMP_DIR/foreign-user-candidate.json" \
    "$(jq -nc --arg user "$SECOND_USER_ID" --arg source "$conversation_id" \
      '{scope:{scope:"USER",scopeId:$user},kind:"PREFERENCE",sourceId:$source,sourceType:"REGRESSION",content:"Cross-user memory pollution",confidence:0.95}')" "$access_token")"
  assert_status_one_of "$foreign_user_propose_status" "403,404" "cross-user MemoryCandidate write isolation"
  assert_error_contract "$TMP_DIR/foreign-user-candidate.json" "cross-user MemoryCandidate write isolation"
  foreign_project_propose_status="$(request POST /api/v1/memory/candidates "$TMP_DIR/foreign-project-candidate.json" \
    "$(jq -nc --arg project "$PROJECT_ID" --arg source "$conversation_id" \
      '{scope:{scope:"PROJECT",scopeId:$project},kind:"DECISION",sourceId:$source,sourceType:"REGRESSION",content:"Foreign project memory pollution",confidence:0.95}')" "$access_token")"
  assert_status_one_of "$foreign_project_propose_status" "403,404" "foreign Project MemoryCandidate rejection"
  assert_error_contract "$TMP_DIR/foreign-project-candidate.json" "foreign Project MemoryCandidate rejection"
  foreign_task_propose_status="$(request POST /api/v1/memory/candidates "$TMP_DIR/foreign-task-candidate.json" \
    "$(jq -nc --arg task "$TASK_ID" --arg source "$conversation_id" \
      '{scope:{scope:"TASK",scopeId:$task},kind:"DECISION",sourceId:$source,sourceType:"REGRESSION",content:"Foreign task memory pollution",confidence:0.95}')" "$access_token")"
  assert_status_one_of "$foreign_task_propose_status" "403,404" "foreign Task MemoryCandidate rejection"
  assert_error_contract "$TMP_DIR/foreign-task-candidate.json" "foreign Task MemoryCandidate rejection"
  foreign_consolidate_status="$(request POST /api/v1/memory/consolidate "$TMP_DIR/foreign-consolidate.json" \
    "$(jq -nc --arg task "$TASK_ID" --arg project "$PROJECT_ID" --arg user "$USER_ID" \
      '{taskId:$task,projectId:$project,userId:$user,acceptThreshold:0.8,promotionThreshold:0.9}')" "$access_token")"
  assert_status_one_of "$foreign_consolidate_status" "403,404" "foreign Task/Project consolidation rejection"
  assert_error_contract "$TMP_DIR/foreign-consolidate.json" "foreign Task/Project consolidation rejection"
  pass "Memory scope authorization rejects unowned USER/PROJECT/TASK writes"

  local recovery_run_id recovery_step_id recovery_checkpoint_id
  recovery_run_id="$(db_query "SELECT gen_random_uuid()")"
  recovery_step_id="$(db_query "SELECT gen_random_uuid()")"
  recovery_checkpoint_id="$(db_query "SELECT gen_random_uuid()")"
  db_query "INSERT INTO platform_agent_runs (id,tenant_id,agent_id,owner_id,conversation_id,state,created_at,updated_at) VALUES ('$recovery_run_id','$TENANT_ID','$agent_id','$USER_ID','$conversation_id','IN_PROGRESS',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"
  db_query "INSERT INTO platform_run_steps (id,agent_run_id,sequence,type,state,created_at) VALUES ('$recovery_step_id','$recovery_run_id',0,'chat-runtime','PENDING',CURRENT_TIMESTAMP)"
  db_query "INSERT INTO platform_run_checkpoints (id,agent_run_id,sequence,state_snapshot,created_at) VALUES ('$recovery_checkpoint_id','$recovery_run_id',0,'{\"phase\":\"inference-pending\",\"stepId\":\"$recovery_step_id\",\"cursor\":\"restart-recovery\"}',CURRENT_TIMESTAMP)"
  [[ "$(db_query "SELECT count(*) FROM platform_agent_runs r JOIN platform_run_steps s ON s.agent_run_id=r.id JOIN platform_run_checkpoints c ON c.agent_run_id=r.id WHERE r.id='$recovery_run_id' AND r.state='IN_PROGRESS' AND s.state='PENDING'")" == "1" ]] \
    || fail "interrupted AgentRun/RunStep/Checkpoint seed was not persisted"
  pass "interrupted AgentRun/RunStep/Checkpoint persisted for post-restart recovery"

  local run_count step_count checkpoint_count snapshot_count recovery_status
  run_count="$(db_query "SELECT count(*) FROM platform_agent_runs WHERE owner_id='$USER_ID'")"
  step_count="$(db_query "SELECT count(*) FROM platform_run_steps s JOIN platform_agent_runs r ON r.id=s.agent_run_id WHERE r.owner_id='$USER_ID'")"
  checkpoint_count="$(db_query "SELECT count(*) FROM platform_run_checkpoints c JOIN platform_agent_runs r ON r.id=c.agent_run_id WHERE r.owner_id='$USER_ID'")"
  snapshot_count="$(db_query "SELECT count(*) FROM platform_conversation_context_snapshots WHERE conversation_id='$conversation_id'")"
  (( run_count >= 5 && step_count >= 5 && checkpoint_count >= 20 && snapshot_count >= 4 )) \
    || fail "Runtime persistence incomplete: runs=$run_count steps=$step_count checkpoints=$checkpoint_count snapshots=$snapshot_count"
  pass "Runtime AgentRun/RunStep/Checkpoint/ConversationSnapshot persistence"
  recovery_status="$(request POST "/api/v1/chat/runs/$agent_run_id/recover" "$TMP_DIR/recovery-terminal.json" \
    '{"reason":"completed run regression"}' "$access_token")"
  assert_status "$recovery_status" 409 "terminal run recovery conflict"
  assert_error_contract "$TMP_DIR/recovery-terminal.json" "recovery conflict"

  local identity_evidence agent_evidence chat_evidence knowledge_evidence
  identity_evidence="$(db_query "SELECT count(*) FROM platform_users u JOIN platform_tenant_memberships m ON m.user_id=u.id JOIN platform_user_credentials c ON c.user_id=u.id WHERE u.id='$USER_ID' AND u.tenant_id='$TENANT_ID' AND m.tenant_id='$TENANT_ID' AND m.status='ACTIVE' AND c.password_hash <> '$PASSWORD_VALUE'")"
  [[ "$identity_evidence" == "1" ]] || fail "Identity tenant/membership/credential persistence mismatch"
  agent_evidence="$(db_query "SELECT count(*) FROM platform_agent_definitions WHERE id='$agent_id' AND owner_id='$USER_ID' AND tenant_id='$TENANT_ID' AND current_agent_version_id IS NOT NULL")"
  [[ "$agent_evidence" == "1" ]] || fail "Agent/provider ownership persistence mismatch"
  chat_evidence="$(db_query "SELECT count(*) FROM platform_messages WHERE conversation_id='$conversation_id'")"
  (( chat_evidence >= 8 )) || fail "Conversation messages were not persisted"
  knowledge_evidence="$(db_query "SELECT count(*) FROM platform_knowledge_chunks WHERE document_id='$document_id' AND embedding_dimensions=1024 AND embedding_vector <> '[]'")"
  (( knowledge_evidence >= 1 )) || fail "Knowledge embedding persistence mismatch"
  pass "PostgreSQL Identity/Agent/Chat/Knowledge ownership evidence"

  mkdir -p "$STATE_DIR"
  jq -n \
    --arg prefix "$PREFIX" --arg username "$username_a" --arg password "$PASSWORD_VALUE" \
    --arg userId "$USER_ID" --arg tenantId "$TENANT_ID" \
    --arg secondUserId "$SECOND_USER_ID" --arg secondTenantId "$SECOND_TENANT_ID" \
    --arg providerId "$provider_id" --arg agentId "$agent_id" \
    --arg documentId "$document_id" --arg conversationId "$conversation_id" \
    --arg agentRunId "$tool_run_id" --arg candidateId "$candidate_id" \
    --arg recoveryRunId "$recovery_run_id" --arg recoveryCheckpointId "$recovery_checkpoint_id" \
    --arg taskId "$TASK_ID" --arg projectId "$PROJECT_ID" \
    --arg rotatedRefresh "$rotated_refresh" \
    '{prefix:$prefix,username:$username,password:$password,userId:$userId,tenantId:$tenantId,
      secondUserId:$secondUserId,secondTenantId:$secondTenantId,providerId:$providerId,
      agentId:$agentId,documentId:$documentId,conversationId:$conversationId,
      agentRunId:$agentRunId,candidateId:$candidateId,recoveryRunId:$recoveryRunId,
      recoveryCheckpointId:$recoveryCheckpointId,taskId:$taskId,projectId:$projectId,
      rotatedRefresh:$rotatedRefresh}' > "$STATE_FILE"
  chmod 600 "$STATE_FILE"
  CLEANUP_ON_EXIT=0
  pass "prepare phase complete; restart state saved without printing credentials"
}

run_verify_restart() {
  load_state
  CLEANUP_ON_EXIT=1
  local username password provider_id agent_id document_id conversation_id agent_run_id recovery_run_id recovery_checkpoint_id rotated_refresh
  username="$(jq -r '.username' "$STATE_FILE")"
  password="$(jq -r '.password' "$STATE_FILE")"
  provider_id="$(jq -r '.providerId' "$STATE_FILE")"
  agent_id="$(jq -r '.agentId' "$STATE_FILE")"
  document_id="$(jq -r '.documentId' "$STATE_FILE")"
  conversation_id="$(jq -r '.conversationId' "$STATE_FILE")"
  agent_run_id="$(jq -r '.agentRunId' "$STATE_FILE")"
  recovery_run_id="$(jq -r '.recoveryRunId' "$STATE_FILE")"
  recovery_checkpoint_id="$(jq -r '.recoveryCheckpointId' "$STATE_FILE")"
  rotated_refresh="$(jq -r '.rotatedRefresh' "$STATE_FILE")"

  local health_status login_status token current_status provider_status agent_status document_status conversation_status history_status
  health_status="$(request GET /actuator/health "$TMP_DIR/restart-health.json")"
  assert_status "$health_status" 200 "restart health"
  login_status="$(request POST /api/v1/auth/login "$TMP_DIR/restart-login.json" \
    "$(jq -nc --arg username "$username" --arg password "$password" '{username:$username,password:$password}')")"
  assert_status "$login_status" 200 "login after restart"
  token="$(jq -r '.data.token' "$TMP_DIR/restart-login.json")"
  current_status="$(request GET /api/v1/users/me "$TMP_DIR/restart-current.json" '' "$token")"
  assert_status "$current_status" 200 "current user after restart"
  provider_status="$(request GET "/api/v1/model-providers/$provider_id" "$TMP_DIR/restart-provider.json" '' "$token")"
  assert_status "$provider_status" 200 "Provider after restart"
  agent_status="$(request GET "/api/v1/agents/$agent_id" "$TMP_DIR/restart-agent.json" '' "$token")"
  assert_status "$agent_status" 200 "Agent after restart"
  document_status="$(request GET "/api/v1/knowledge/documents/$document_id" "$TMP_DIR/restart-document.json" '' "$token")"
  assert_status "$document_status" 200 "Knowledge after restart"
  jq -e '.data.status == "READY"' "$TMP_DIR/restart-document.json" >/dev/null \
    || fail "Knowledge status lost after restart"
  conversation_status="$(request GET "/api/v1/chat/conversations/$conversation_id" "$TMP_DIR/restart-conversation.json" '' "$token")"
  assert_status "$conversation_status" 200 "Conversation after restart"
  history_status="$(request GET "/api/v1/chat/conversations/$conversation_id/messages" "$TMP_DIR/restart-history.json" '' "$token")"
  assert_status "$history_status" 200 "messages after restart"
  jq -e '(.data | length) >= 8' "$TMP_DIR/restart-history.json" >/dev/null \
    || fail "Conversation history lost after restart"
  [[ "$(db_query "SELECT count(*) FROM platform_agent_runs WHERE id='$agent_run_id' AND owner_id='$USER_ID'")" == "1" ]] \
    || fail "AgentRun lost after restart"
  [[ "$(db_query "SELECT count(*) FROM platform_run_checkpoints WHERE agent_run_id='$agent_run_id'")" -ge "1" ]] \
    || fail "Checkpoint lost after restart"
  [[ "$(db_query "SELECT count(*) FROM platform_tool_execution_ledger WHERE agent_run_id='$agent_run_id' AND status='SUCCEEDED'")" == "1" ]] \
    || fail "ToolExecutionLedger lost after restart"
  pass "Runtime/checkpoint/tool ledger durability after restart"

  local recovery_status
  [[ "$(db_query "SELECT count(*) FROM platform_agent_runs r JOIN platform_run_steps s ON s.agent_run_id=r.id JOIN platform_run_checkpoints c ON c.agent_run_id=r.id WHERE r.id='$recovery_run_id' AND r.owner_id='$USER_ID' AND r.state='IN_PROGRESS' AND s.state='PENDING' AND c.id='$recovery_checkpoint_id'")" == "1" ]] \
    || fail "interrupted AgentRun/RunStep/Checkpoint lost after restart"
  recovery_status="$(request POST "/api/v1/chat/runs/$recovery_run_id/recover" "$TMP_DIR/recovery.json" \
    '{"reason":"backend regression restart"}' "$token")"
  assert_status "$recovery_status" 200 "AgentRun recovery after restart"
  jq -e --arg checkpoint "$recovery_checkpoint_id" \
    '.data.resumed == true and .data.state == "COMPLETED" and .data.checkpointId == $checkpoint' \
    "$TMP_DIR/recovery.json" >/dev/null || fail "recovery response did not resume from persisted checkpoint"
  [[ "$(db_query "SELECT count(*) FROM platform_run_recoveries WHERE agent_run_id='$recovery_run_id' AND state='COMPLETED'")" == "1" ]] \
    || fail "Recovery completion was not persisted"
  [[ "$(db_query "SELECT count(*) FROM platform_agent_runs WHERE id='$recovery_run_id' AND state='IN_PROGRESS'")" == "1" ]] \
    || fail "Recovered AgentRun did not return to IN_PROGRESS"
  pass "AgentRun recovery reconstructed persisted RunStep/Checkpoint after restart"

  local refresh_status logout_status revoked_access_status revoked_refresh_status
  refresh_status="$(request POST /api/v1/auth/refresh "$TMP_DIR/restart-refresh.json" \
    "$(jq -nc --arg refreshToken "$rotated_refresh" '{refreshToken:$refreshToken}')")"
  assert_status "$refresh_status" 200 "rotated refresh survives restart"
  local final_refresh="$(jq -r '.data.refreshToken' "$TMP_DIR/restart-refresh.json")"
  logout_status="$(request POST /api/v1/auth/logout "$TMP_DIR/logout.json" \
    "$(jq -nc --arg refreshToken "$final_refresh" '{refreshToken:$refreshToken}')" "$token")"
  assert_status "$logout_status" 200 "logout"
  revoked_access_status="$(request GET /api/v1/users/me "$TMP_DIR/revoked-access.json" '' "$token")"
  assert_status "$revoked_access_status" 401 "access token rejected after logout"
  revoked_refresh_status="$(request POST /api/v1/auth/refresh "$TMP_DIR/revoked-refresh.json" \
    "$(jq -nc --arg refreshToken "$final_refresh" '{refreshToken:$refreshToken}')")"
  assert_status "$revoked_refresh_status" 401 "refresh token rejected after logout"
  pass "restart verification complete"
}

case "$PHASE" in
  prepare)
    run_prepare
    ;;
  verify-restart)
    run_verify_restart
    ;;
  cleanup)
    load_state
    CLEANUP_ON_EXIT=1
    pass "cleanup requested"
    ;;
  *)
    fail "unsupported REGRESSION_PHASE: $PHASE (use prepare, verify-restart, or cleanup)"
    ;;
esac
