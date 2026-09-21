#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:19000}"
SECRET_ENV_FILE="${SECRET_ENV_FILE:-}"
EVIDENCE_FILE="${E2E_EVIDENCE_FILE:-}"
CHAT_MODEL="${QWEN_CHAT_MODEL:-qwen-plus}"
TMP_DIR="$(mktemp -d)"
RUN_ID="$(date +%s)-$$"
PASSWORD_VALUE="RealE2ePassword123!"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }
trap 'rm -rf "$TMP_DIR"' EXIT
for binary in curl jq shasum; do command -v "$binary" >/dev/null 2>&1 || fail "$binary is required"; done
[[ -f "$SECRET_ENV_FILE" ]] || fail "SECRET_ENV_FILE is required"
[[ "$(stat -f '%Lp' "$SECRET_ENV_FILE")" == "600" ]] || fail "secret environment must be mode 600"
# shellcheck disable=SC1090
source "$SECRET_ENV_FILE"
[[ -n "${QWEN_API_KEY:-}" && "${QWEN_OPENAI_BASE_URL:-}" == https://* ]] \
  || fail "Qwen credential environment is incomplete"

request() {
  local method="$1" path="$2" output="$3" body="${4:-}" token="${5:-}"
  local args=(-sS -o "$output" -w '%{http_code}' --connect-timeout 10 --max-time 120
    -X "$method" "$BASE_URL$path")
  [[ -z "$body" ]] || args+=(-H 'Content-Type: application/json' --data-binary @-)
  [[ -z "$token" ]] || args+=(-H "Authorization: Bearer $token")
  if [[ -n "$body" ]]; then
    printf '%s' "$body" | curl "${args[@]}"
  else
    curl "${args[@]}"
  fi
}
expect() {
  local actual="$1" expected="$2" label="$3" file="$4"
  if [[ "$actual" != "$expected" ]]; then
    code="$(jq -r '.code // "UNKNOWN"' "$file" 2>/dev/null || true)"
    fail "$label expected HTTP $expected, got $actual ($code)"
  fi
  pass "$label"
}

status="$(request GET /actuator/health/readiness "$TMP_DIR/readiness.json")"
expect "$status" 200 "Trusted Beta readiness" "$TMP_DIR/readiness.json"
jq -e '.status == "UP"' "$TMP_DIR/readiness.json" >/dev/null || fail "readiness is not UP"

username="real-e2e-$RUN_ID@example.com"
status="$(request POST /api/v1/auth/register "$TMP_DIR/register.json" \
  "$(jq -nc --arg username "$username" --arg password "$PASSWORD_VALUE" \
    '{username:$username,password:$password,displayName:"Real E2E"}')")"
expect "$status" 200 "Identity/Organization registration" "$TMP_DIR/register.json"
token="$(jq -r '.data.token' "$TMP_DIR/register.json")"

status="$(request POST /api/v1/model-providers "$TMP_DIR/provider.json" \
  "$(jq -nc --arg name "Qwen Real E2E $RUN_ID" --arg base "$QWEN_OPENAI_BASE_URL" \
    --arg key "$QWEN_API_KEY" --arg model "$CHAT_MODEL" \
    '{name:$name,type:"openai-compatible",baseUrl:$base,apiKey:$key,authType:"bearer",
      enabled:true,isDefault:true,models:[{modelId:$model,displayName:$model,
      maxContextTokens:32768,isDefault:true}]}')" "$token")"
expect "$status" 201 "Qwen Provider creation" "$TMP_DIR/provider.json"
provider_id="$(jq -r '.data.id' "$TMP_DIR/provider.json")"
grep -Fq "$QWEN_API_KEY" "$TMP_DIR/provider.json" && fail "Provider API key leaked" || true
jq -e '.data.apiKey == "configured"' "$TMP_DIR/provider.json" >/dev/null \
  || fail "Provider secret redaction contract failed"

status="$(request POST "/api/v1/model-providers/$provider_id/test" \
  "$TMP_DIR/provider-test.json" '' "$token")"
expect "$status" 200 "real Provider /models connection test" "$TMP_DIR/provider-test.json"
jq -e '.data.success == true and .data.status == "ACTIVE"' "$TMP_DIR/provider-test.json" >/dev/null \
  || fail "Qwen Provider connection is not ACTIVE"

status="$(request GET "/api/v1/model-providers/$provider_id/models" \
  "$TMP_DIR/models.json" '' "$token")"
expect "$status" 200 "Provider model list" "$TMP_DIR/models.json"
provider_model_id="$(jq -r --arg model "$CHAT_MODEL" '.data[] | select(.modelId==$model) | .id' \
  "$TMP_DIR/models.json" | head -1)"
[[ -n "$provider_model_id" ]] || fail "configured Qwen model not found"

status="$(request POST "/api/v1/model-providers/$provider_id/models/$CHAT_MODEL/test" \
  "$TMP_DIR/model-test.json" '' "$token")"
expect "$status" 200 "real Provider model inference test" "$TMP_DIR/model-test.json"
jq -e '.data.success == true and (.data.responsePreview|length)>0 and .data.inputTokens>0 and .data.outputTokens>0' "$TMP_DIR/model-test.json" >/dev/null \
  || fail "configured Qwen model inference test failed"
model_test_input_tokens="$(jq -r '.data.inputTokens' "$TMP_DIR/model-test.json")"
model_test_output_tokens="$(jq -r '.data.outputTokens' "$TMP_DIR/model-test.json")"
model_test_hash="$(jq -r '.data.responsePreview' "$TMP_DIR/model-test.json" \
  | shasum -a 256 | awk '{print $1}')"

status="$(request POST /api/v1/model-pools "$TMP_DIR/pool.json" \
  "$(jq -nc --arg name "Real E2E Pool $RUN_ID" \
    '{name:$name,visibility:"PRIVATE",routingStrategy:"PRIORITY",fallbackEnabled:false}')" "$token")"
expect "$status" 201 "ModelPool creation" "$TMP_DIR/pool.json"
pool_id="$(jq -r '.data.id' "$TMP_DIR/pool.json")"
status="$(request POST "/api/v1/model-pools/$pool_id/members" "$TMP_DIR/member.json" \
  "$(jq -nc --arg provider "$provider_id" --arg model "$provider_model_id" \
    '{providerId:$provider,providerModelId:$model,priority:0,weight:1}')" "$token")"
expect "$status" 201 "ModelPool member" "$TMP_DIR/member.json"
status="$(request POST "/api/v1/model-pools/$pool_id/activate" "$TMP_DIR/pool-active.json" '' "$token")"
expect "$status" 200 "ModelPool activation" "$TMP_DIR/pool-active.json"
jq -e '.data.status == "ACTIVE"' "$TMP_DIR/pool-active.json" >/dev/null || fail "pool is not ACTIVE"

status="$(request POST /api/v1/knowledge/documents "$TMP_DIR/document.json" \
  "$(jq -nc --arg name "real-e2e-$RUN_ID.txt" \
    '{name:$name,contentType:"text/plain",storageLocation:"inline:real-e2e"}')" "$token")"
expect "$status" 200 "Knowledge document creation" "$TMP_DIR/document.json"
document_id="$(jq -r '.data.id' "$TMP_DIR/document.json")"
marker="SA-REAL-E2E-$RUN_ID"
status="$(request POST "/api/v1/knowledge/documents/$document_id/process" \
  "$TMP_DIR/process.json" "$(jq -nc --arg content "The verification marker is $marker." \
    '{content:$content}')" "$token")"
expect "$status" 200 "real Qwen Embedding processing" "$TMP_DIR/process.json"
jq -e '.data.document.status == "READY" and (.data.chunks|length)>0' "$TMP_DIR/process.json" >/dev/null \
  || fail "Knowledge processing did not become READY"
status="$(request POST /api/v1/knowledge/retrieve "$TMP_DIR/retrieve.json" \
  "$(jq -nc --arg id "$document_id" --arg marker "$marker" \
    '{documentIds:[$id],query:$marker,topK:3}')" "$token")"
expect "$status" 200 "real Embedding retrieval" "$TMP_DIR/retrieve.json"
rag_matches="$(jq '.data.matches|length' "$TMP_DIR/retrieve.json")"
(( rag_matches > 0 )) || fail "RAG returned no matches"

status="$(request POST /api/v1/agents "$TMP_DIR/agent.json" \
  "$(jq -nc --arg name "Real E2E Agent $RUN_ID" --arg pool "$pool_id" --arg doc "$document_id" \
    '{name:$name,systemPrompt:"Answer concisely and preserve the supplied marker.",modelPoolId:$pool,
      temperature:0.1,maxTokens:256,maxTurns:4,permissionMode:"private",memoryEnabled:true,
      ragEnabled:true,networkEnabled:false,knowledgeBaseIds:[$doc]}')" "$token")"
expect "$status" 201 "Agent bound to ModelPool" "$TMP_DIR/agent.json"
agent_id="$(jq -r '.data.id' "$TMP_DIR/agent.json")"
status="$(request POST /api/v1/chat/conversations "$TMP_DIR/conversation.json" \
  "$(jq -nc --arg agent "$agent_id" '{agentId:$agent,name:"Real E2E"}')" "$token")"
expect "$status" 200 "Conversation creation" "$TMP_DIR/conversation.json"
conversation_id="$(jq -r '.data.conversationId' "$TMP_DIR/conversation.json")"

status="$(request POST /api/v1/chat/messages "$TMP_DIR/chat.json" \
  "$(jq -nc --arg agent "$agent_id" --arg conversation "$conversation_id" --arg marker "$marker" \
    '{agentId:$agent,conversationId:$conversation,message:("Return this marker: "+$marker)}')" "$token")"
expect "$status" 200 "real Qwen Chat" "$TMP_DIR/chat.json"
input_tokens="$(jq -r '.data.inputTokenCount' "$TMP_DIR/chat.json")"
output_tokens="$(jq -r '.data.outputTokenCount' "$TMP_DIR/chat.json")"
assistant="$(jq -r '.data.assistantMessage' "$TMP_DIR/chat.json")"
[[ -n "$assistant" && "$assistant" != "null" ]] || fail "assistant response is empty"
(( input_tokens > 0 && output_tokens > 0 )) || fail "real Provider token usage is missing"
assistant_hash="$(printf '%s' "$assistant" | shasum -a 256 | awk '{print $1}')"

sse_status="$(curl -sS -o "$TMP_DIR/sse.txt" -w '%{http_code}' --connect-timeout 10 --max-time 120 \
  -H "Authorization: Bearer $token" -H 'Accept: text/event-stream' \
  -H 'Content-Type: application/json' --data "$(jq -nc --arg agent "$agent_id" \
  --arg conversation "$conversation_id" '{agentId:$agent,conversationId:$conversation,
  message:"Reply with the single word ready."}')" "$BASE_URL/api/v1/chat/messages/stream")"
[[ "$sse_status" == "200" ]] || fail "SSE returned HTTP $sse_status"
grep -q 'event:done' "$TMP_DIR/sse.txt" || fail "SSE done event missing"
pass "real Qwen SSE"

status="$(request GET "/api/v1/model-providers/$provider_id/health-observations?limit=10" \
  "$TMP_DIR/health.json" '' "$token")"
expect "$status" 200 "Provider health evidence" "$TMP_DIR/health.json"
health_count="$(jq '.data|length' "$TMP_DIR/health.json")"
(( health_count > 0 )) || fail "Provider health observation missing"
status="$(request GET /api/v1/inference-budget/usage "$TMP_DIR/usage.json" '' "$token")"
expect "$status" 200 "Inference usage evidence" "$TMP_DIR/usage.json"

if [[ -n "$EVIDENCE_FILE" ]]; then
  mkdir -p "$(dirname "$EVIDENCE_FILE")"
  umask 077
  jq -n --arg providerStatus "ACTIVE" --arg model "$CHAT_MODEL" \
    --arg assistantSha256 "$assistant_hash" --argjson inputTokens "$input_tokens" \
    --argjson outputTokens "$output_tokens" --argjson ragMatches "$rag_matches" \
    --argjson healthObservations "$health_count" \
    --arg modelTestSha256 "$model_test_hash" \
    --argjson modelTestInputTokens "$model_test_input_tokens" \
    --argjson modelTestOutputTokens "$model_test_output_tokens" \
    '{status:"PASS",providerStatus:$providerStatus,model:$model,
      assistantSha256:$assistantSha256,inputTokens:$inputTokens,outputTokens:$outputTokens,
      ragMatches:$ragMatches,healthObservations:$healthObservations,
      modelTestSha256:$modelTestSha256,modelTestInputTokens:$modelTestInputTokens,
      modelTestOutputTokens:$modelTestOutputTokens,secretsRedacted:true}' \
    > "$EVIDENCE_FILE"
  chmod 600 "$EVIDENCE_FILE"
fi
echo "PASS: real Qwen Chat/Embedding/ModelPool/RAG/SSE E2E; evidence is redacted"
