#!/usr/bin/env bash
set -euo pipefail

MODE="${1:---describe}"
CASES=(escape resource-cpu-memory-pids network-none-dns exact-workspace-mount timeout-kill labeled-orphan-cleanup)

case "$MODE" in
  --describe)
    printf '%s\n' "${CASES[@]}"
    ;;
  --execute)
    echo "BLOCKED: M65-PR1-U02 owns real Linux/runsc execution; U01 does not run containers or enable product flags." >&2
    exit 3
    ;;
  *)
    echo "usage: $0 [--describe|--execute]" >&2
    exit 1
    ;;
esac
