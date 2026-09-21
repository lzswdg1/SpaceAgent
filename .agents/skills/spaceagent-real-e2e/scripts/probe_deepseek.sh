#!/usr/bin/env bash
set -euo pipefail

SECRET_ENV_FILE="${SECRET_ENV_FILE:-}"
[[ -f "$SECRET_ENV_FILE" ]] || { echo "SKIP: no secret environment"; exit 0; }
# shellcheck disable=SC1090
source "$SECRET_ENV_FILE"
[[ -n "${DEEPSEEK_API_KEY:-}" ]] || { echo "SKIP: DeepSeek key unavailable"; exit 0; }
status="$(printf 'header = "Authorization: Bearer %s"\n' "$DEEPSEEK_API_KEY" \
  | curl --config - -sS -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 20 \
  https://api.deepseek.com/models || true)"
if [[ "$status" == "200" ]]; then
  echo "PASS_OPTIONAL: DeepSeek model-list probe succeeded"
else
  echo "BLOCKED_OPTIONAL: DeepSeek probe returned HTTP $status; Qwen remains authoritative"
fi
