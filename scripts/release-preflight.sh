#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${RELEASE_ENV_FILE:-$ROOT_DIR/.env.release}"
SKIP_BUILD="${RELEASE_PREFLIGHT_SKIP_BUILD:-0}"
ALLOW_DIRTY="${RELEASE_PREFLIGHT_ALLOW_DIRTY:-0}"
BASE_URL="${RELEASE_BASE_URL:-}"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }
require_bin() { command -v "$1" >/dev/null 2>&1 || fail "$1 is required"; }

for binary in bash docker git grep curl python3; do require_bin "$binary"; done
[[ -f "$ENV_FILE" ]] || fail "release environment file not found: $ENV_FILE"

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

python3 "$ROOT_DIR/scripts/check-release-environment.py" || fail "release environment validation"

if [[ "${CHAT_AUTOMATIC_PLANNING_ENABLED:-false}" == "true" ]]; then
  [[ "${MULTI_AGENT_ORCHESTRATOR_MODE:-none}" == "http" ]] \
    || fail "automatic Chat planning requires MULTI_AGENT_ORCHESTRATOR_MODE=http"
  [[ -n "${MULTI_AGENT_ORCHESTRATOR_BASE_URL:-}" ]] \
    || fail "automatic Chat planning requires MULTI_AGENT_ORCHESTRATOR_BASE_URL"
  multi_agent_token="${MULTI_AGENT_ORCHESTRATOR_INTERNAL_TOKEN:-${INTERNAL_SERVICE_TOKEN}}"
  (( ${#multi_agent_token} >= 32 )) \
    || fail "automatic Chat planning requires a 32+ character orchestrator token"
fi

if [[ "${SANDBOX_MODE:-in-process}" == "http" ]]; then
  sandbox_token="${SANDBOX_INTERNAL_TOKEN:-${INTERNAL_SERVICE_TOKEN}}"
  (( ${#sandbox_token} >= 32 )) || fail "SANDBOX_INTERNAL_TOKEN must contain at least 32 characters"
  [[ "${SANDBOX_CONTAINER_USER:-999:999}" != "root" \
      && "${SANDBOX_CONTAINER_USER:-999:999}" != "0" \
      && "${SANDBOX_CONTAINER_USER:-999:999}" != "0:0" ]] \
    || fail "sandbox execution container must be non-root"
  docker_api="$(docker version --format '{{.Server.APIVersion}}' 2>/dev/null)" \
    || fail "Docker Engine is required for SANDBOX_MODE=http"
  docker_api_minor="${docker_api#1.}"
  [[ "$docker_api" == 1.* && "$docker_api_minor" =~ ^[0-9]+$ \
      && "$docker_api_minor" -ge 45 ]] \
    || fail "sandbox requires Docker API 1.45 / Engine 26+"
  docker compose --profile sandbox --env-file "$ENV_FILE" \
    -f "$ROOT_DIR/docker-compose.yml" -f "$ROOT_DIR/docker-compose.release.yml" \
    config --quiet
  pass "container sandbox Compose and Docker API"
fi

docker compose --env-file "$ENV_FILE" -f "$ROOT_DIR/docker-compose.yml" \
  -f "$ROOT_DIR/docker-compose.release.yml" config --quiet
pass "strict release Compose resolves"

if [[ "$SKIP_BUILD" != "1" ]]; then
  "$ROOT_DIR/mvnw" -q -DskipTests package
  "$ROOT_DIR/scripts/check-architecture.sh"
  pass "backend package and architecture gates"
fi

git -C "$ROOT_DIR" diff --check
if [[ "$ALLOW_DIRTY" != "1" && -n "$(git -C "$ROOT_DIR" status --porcelain)" ]]; then
  fail "release worktree must be clean"
fi
pass "Git release state"

if [[ -n "$BASE_URL" ]]; then
  readiness="$(curl -fsS --connect-timeout 5 --max-time 15 \
    "$BASE_URL/actuator/health/readiness")"
  grep -q '"status":"UP"' <<<"$readiness" || fail "release readiness is not UP"
  pass "live release readiness"
fi

echo "Release preflight passed for $SPACEAGENT_RELEASE_VERSION (secrets redacted)."
