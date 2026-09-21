#!/usr/bin/env bash
set -euo pipefail

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="$(cd "$SKILL_DIR/../../.." && pwd)"
CREDENTIAL_DIR="${1:-$ROOT_DIR/testapikey}"
RUN_ID="$(date +%s)-$$"
RUN_DIR="${REAL_E2E_RUN_DIR:-$ROOT_DIR/.run/real-e2e/$RUN_ID}"
SECRET_ENV="$RUN_DIR/secrets.env"
EVIDENCE="$RUN_DIR/model-evidence.json"
APP_LOG="$RUN_DIR/platform.log"
COMPOSE_PROJECT="spaceagent-real-e2e-$RUN_ID"
POSTGRES_NAME="spaceagent-real-e2e-postgres-$RUN_ID"
APP_PID=""

fail() { echo "FAIL: $*" >&2; exit 1; }
for binary in docker java curl jq openssl python3; do command -v "$binary" >/dev/null 2>&1 || fail "$binary is required"; done
mkdir -p "$RUN_DIR/workspaces"
chmod 700 "$RUN_DIR"
"$SKILL_DIR/scripts/inspect_credentials.sh" "$CREDENTIAL_DIR"
"$SKILL_DIR/scripts/materialize_secret_env.sh" "$CREDENTIAL_DIR" "$SECRET_ENV"
# shellcheck disable=SC1090
source "$SECRET_ENV"

free_port() {
  python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()'
}
DB_PORT="$(free_port)"
APP_PORT="$(free_port)"
DB_PASSWORD_VALUE="$(openssl rand -hex 24)"
JWT_VALUE="$(openssl rand -hex 32)"
INTERNAL_VALUE="$(openssl rand -hex 32)"
MODEL_CIPHER_VALUE="$(openssl rand -hex 32)"
MCP_CIPHER_VALUE="$(openssl rand -hex 32)"
PROVIDER_HOST="$(python3 -c 'import sys,urllib.parse; print(urllib.parse.urlparse(sys.argv[1]).hostname or "")' "$QWEN_OPENAI_BASE_URL")"
[[ -n "$PROVIDER_HOST" ]] || fail "unable to resolve Qwen provider host"

cleanup() {
  status=$?
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" >/dev/null 2>&1; then
    kill "$APP_PID" >/dev/null 2>&1 || true
    for _ in $(seq 1 30); do kill -0 "$APP_PID" >/dev/null 2>&1 || break; sleep 1; done
    kill -9 "$APP_PID" >/dev/null 2>&1 || true
  fi
  COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" POSTGRES_CONTAINER_NAME="$POSTGRES_NAME" \
    POSTGRES_HOST_PORT="$DB_PORT" DB_PASSWORD="$DB_PASSWORD_VALUE" \
    docker compose -f "$ROOT_DIR/docker-compose.yml" down -v --remove-orphans \
    >/dev/null 2>&1 || true
  if [[ -f "$APP_LOG" ]]; then
    if grep -Fq "$QWEN_API_KEY" "$APP_LOG" \
      || { [[ -n "${DEEPSEEK_API_KEY:-}" ]] && grep -Fq "$DEEPSEEK_API_KEY" "$APP_LOG"; }; then
      rm -f "$APP_LOG"
      echo "FAIL: provider credential appeared in application log; log removed" >&2
      status=1
    fi
  fi
  rm -f "$SECRET_ENV"
  exit "$status"
}
trap cleanup EXIT INT TERM

"$ROOT_DIR/mvnw" -q -DskipTests package
COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" POSTGRES_CONTAINER_NAME="$POSTGRES_NAME" \
  POSTGRES_HOST_PORT="$DB_PORT" DB_PASSWORD="$DB_PASSWORD_VALUE" \
  docker compose -f "$ROOT_DIR/docker-compose.yml" up -d postgres >/dev/null

for _ in $(seq 1 60); do
  state="$(docker inspect -f '{{.State.Health.Status}}' "$POSTGRES_NAME" 2>/dev/null || true)"
  [[ "$state" == "healthy" ]] && break
  sleep 1
done
[[ "$(docker inspect -f '{{.State.Health.Status}}' "$POSTGRES_NAME" 2>/dev/null)" == "healthy" ]] \
  || fail "isolated PostgreSQL did not become healthy"

umask 077
PLATFORM_SERVER_PORT="$APP_PORT" \
SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$DB_PORT/spaceagent_platform" \
SPRING_DATASOURCE_USERNAME=spaceagent SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD_VALUE" \
PLATFORM_PERSISTENCE=postgres PLATFORM_JWT_SECRET="$JWT_VALUE" \
PLATFORM_INTERNAL_TOKEN="$INTERNAL_VALUE" \
PLATFORM_INFERENCE_MODEL_PROVIDER_ENCRYPTION_KEY="$MODEL_CIPHER_VALUE" \
PLATFORM_TOOLING_MCP_ENCRYPTION_KEY="$MCP_CIPHER_VALUE" \
PLATFORM_INFERENCE_EXECUTION_MODE=http \
PLATFORM_INFERENCE_ALLOWED_PROVIDER_HOSTS="$PROVIDER_HOST" \
PLATFORM_INFERENCE_ALLOW_LOCAL_PROVIDER_HOSTS=false \
PLATFORM_KNOWLEDGE_EMBEDDING_MODE=http \
PLATFORM_KNOWLEDGE_EMBEDDING_BASE_URL="$QWEN_OPENAI_BASE_URL" \
PLATFORM_KNOWLEDGE_EMBEDDING_API_KEY="$QWEN_API_KEY" \
PLATFORM_KNOWLEDGE_EMBEDDING_MODEL="${QWEN_EMBEDDING_MODEL:-text-embedding-v4}" \
PLATFORM_WORKSPACE_MANAGED_ROOT="$RUN_DIR/workspaces" \
PLATFORM_RELEASE_MODE=trusted-beta SPACEAGENT_RELEASE_VERSION="real-e2e-$RUN_ID" \
PLATFORM_EXPECTED_SCHEMA_VERSION=1035 PLATFORM_TRUSTED_CODE_ONLY=true \
PLATFORM_PUBLIC_UNTRUSTED_CODE_ENABLED=false PLATFORM_ALLOW_INSECURE_LOCAL=false \
java -jar "$ROOT_DIR/apps/platform-server/target/platform-server-0.0.1-SNAPSHOT-exec.jar" \
  > "$APP_LOG" 2>&1 &
APP_PID=$!

for _ in $(seq 1 90); do
  if curl -fsS --connect-timeout 2 --max-time 5 \
    "http://127.0.0.1:$APP_PORT/actuator/health/readiness" | grep -q '"status":"UP"'; then
    break
  fi
  kill -0 "$APP_PID" >/dev/null 2>&1 || fail "platform-server exited during startup"
  sleep 2
done
curl -fsS "http://127.0.0.1:$APP_PORT/actuator/health/readiness" | grep -q '"status":"UP"' \
  || fail "platform-server readiness did not become UP"

BASE_URL="http://127.0.0.1:$APP_PORT" SECRET_ENV_FILE="$SECRET_ENV" \
  E2E_EVIDENCE_FILE="$EVIDENCE" "$SKILL_DIR/scripts/run_model_e2e.sh"
SECRET_ENV_FILE="$SECRET_ENV" "$SKILL_DIR/scripts/probe_deepseek.sh"
"$SKILL_DIR/scripts/inspect_github_prerequisites.sh"

echo "PASS: isolated real-model E2E complete"
echo "Evidence: $EVIDENCE"
