#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXPECTED_MODULE='github.com/lzswdg1/SpaceAgent/cli'
EXPECTED_CLONE='https://github.com/lzswdg1/SpaceAgent.git'
UNRESOLVED_CLONE_MARKER='REPLACE_WITH_PUBLIC_'"REPOSITORY_URL"

if rg -q "$UNRESOLVED_CLONE_MARKER" "$ROOT_DIR/CONTRIBUTING.md"; then
  printf '%s\n' 'FAIL: CONTRIBUTING clone URL is unresolved' >&2
  exit 1
fi
grep -Fxq "module $EXPECTED_MODULE" "$ROOT_DIR/cli/go.mod" \
  || { printf '%s\n' 'FAIL: CLI module does not match the public repository' >&2; exit 1; }
grep -Fq "git clone $EXPECTED_CLONE spaceagent" "$ROOT_DIR/CONTRIBUTING.md" \
  || { printf '%s\n' 'FAIL: CONTRIBUTING clone URL does not match the public repository' >&2; exit 1; }

printf '%s\n' 'PASS: public repository identity blockers are resolved'
