#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PHASE="${RELEASE_GOLDEN_PATH_PHASE:-full}"
BASE_URL="${BASE_URL:-http://127.0.0.1:9000}"

wait_ready() {
  for _ in $(seq 1 60); do
    if curl -fsS --connect-timeout 2 --max-time 5 \
      "$BASE_URL/actuator/health/readiness" | grep -q '"status":"UP"'; then
      return
    fi
    sleep 2
  done
  echo "FAIL: platform readiness did not recover" >&2
  exit 1
}

case "$PHASE" in
  prepare)
    REGRESSION_PHASE=prepare "$ROOT_DIR/scripts/regression-platform-api.sh"
    ;;
  verify-restart)
    REGRESSION_PHASE=verify-restart "$ROOT_DIR/scripts/regression-platform-api.sh"
    ;;
  cleanup)
    REGRESSION_PHASE=cleanup "$ROOT_DIR/scripts/regression-platform-api.sh"
    ;;
  full)
    [[ "${RELEASE_GOLDEN_PATH_DISPOSABLE_DATABASE:-}" == "YES" ]] || {
      echo "FAIL: full golden path requires RELEASE_GOLDEN_PATH_DISPOSABLE_DATABASE=YES" >&2
      exit 2
    }
    REGRESSION_PHASE=prepare "$ROOT_DIR/scripts/regression-platform-api.sh"
    docker compose -f "$ROOT_DIR/docker-compose.yml" restart platform-server
    wait_ready
    REGRESSION_PHASE=verify-restart "$ROOT_DIR/scripts/regression-platform-api.sh"
    ;;
  *)
    echo "FAIL: RELEASE_GOLDEN_PATH_PHASE must be full, prepare, verify-restart, or cleanup" >&2
    exit 2
    ;;
esac
