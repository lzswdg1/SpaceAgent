#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT_DIR/scripts/verify-runsc-environment.sh" --static >/dev/null
cases="$($ROOT_DIR/scripts/run-runsc-isolation-acceptance.sh --describe)"
for expected in escape resource-cpu-memory-pids network-none-dns exact-workspace-mount timeout-kill labeled-orphan-cleanup; do
  grep -qx "$expected" <<<"$cases" || { echo "missing runsc acceptance case: $expected" >&2; exit 1; }
done
if "$ROOT_DIR/scripts/run-runsc-isolation-acceptance.sh" --execute >/dev/null 2>&1; then
  echo "U01 execution guard unexpectedly allowed real acceptance" >&2
  exit 1
fi
[[ "${PLATFORM_PUBLIC_UNTRUSTED_CODE_ENABLED:-false}" == "false" ]]
echo "runsc acceptance static checks passed"
