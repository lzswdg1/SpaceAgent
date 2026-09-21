#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT_DIR/ops/acceptance/runsc-environment.json"
MODE="${1:---static}"
fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }
require_bin() { command -v "$1" >/dev/null 2>&1 || fail "$1 is required"; }

static_check() {
  [[ -f "$MANIFEST" ]] || fail "runsc environment manifest is missing"
  grep -q '"runscVersion": "release-20260817.0"' "$MANIFEST" || fail "runsc version is not pinned"
  grep -q '"minimumDockerApi": "1.45"' "$MANIFEST" || fail "Docker API minimum is not pinned"
  grep -q '"requiredBeforeAcceptance": "false"' "$MANIFEST" || fail "public-untrusted switch must remain false"
  grep -q '"changedByAcceptance": false' "$MANIFEST" || fail "acceptance must not change product flags"
  bash -n "$ROOT_DIR/scripts/run-runsc-isolation-acceptance.sh"
  pass "runsc manifest and acceptance scripts are structurally pinned"
}

version_ge() {
  local actual="$1" minimum="$2"
  [[ "$(printf '%s\n%s\n' "$minimum" "$actual" | sort -V | head -1)" == "$minimum" ]]
}

probe() {
  static_check
  [[ "$(uname -s)" == "Linux" ]] || fail "Linux host is required"
  require_bin docker; require_bin runsc
  kernel="$(uname -r | cut -d- -f1)"
  version_ge "$kernel" "6.1.0" || fail "Linux kernel 6.1+ is required"
  arch="$(uname -m)"
  [[ "$arch" == "x86_64" || "$arch" == "aarch64" ]] || fail "unsupported architecture"
  docker_api="$(docker version --format '{{.Server.APIVersion}}')"
  version_ge "$docker_api" "1.45" || fail "Docker API 1.45+ is required"
  docker_version="$(docker version --format '{{.Server.Version}}')"
  version_ge "$docker_version" "26.0.0" || fail "Docker Engine 26+ is required"
  docker info --format '{{json .Runtimes}}' | grep -q '"runsc"' || fail "runsc runtime is not registered"
  runsc_version="$(runsc --version | awk '/^runsc version /{print $3; exit}')"
  [[ "$runsc_version" == "release-20260817.0" ]] || fail "runsc version does not match manifest"
  image="${SANDBOX_EXECUTION_IMAGE:-}"
  [[ "$image" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "SANDBOX_EXECUTION_IMAGE must be an immutable sha256 ID"
  [[ "${PLATFORM_PUBLIC_UNTRUSTED_CODE_ENABLED:-false}" == "false" ]] || fail "public-untrusted switch must remain disabled during acceptance"
  pass "Linux/Docker/runsc environment matches the pinned manifest"
}

case "$MODE" in
  --static) static_check ;;
  --probe) probe ;;
  *) fail "usage: $0 [--static|--probe]" ;;
esac
