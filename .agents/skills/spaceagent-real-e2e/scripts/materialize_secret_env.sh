#!/usr/bin/env bash
set -euo pipefail

DIR="${1:-}"
OUTPUT="${2:-}"
[[ -d "$DIR" && -n "$OUTPUT" ]] || { echo "usage: materialize_secret_env.sh <credential-dir> <output>" >&2; exit 2; }
qwen="$(find "$DIR" -maxdepth 1 -type f -name '*.csv' -print -quit)"
[[ -f "$qwen" ]] || { echo "Qwen CSV not found" >&2; exit 2; }
mkdir -p "$(dirname "$OUTPUT")"
umask 077

csv_value() {
  local label="$1"
  awk -F',' -v wanted="$label" '{
    gsub(/^﻿/,"",$1)
    if($1==wanted){sub(/\r$/, "", $2); gsub(/^"|"$/, "", $2); print $2; exit}
  }' "$qwen"
}

qwen_key="$(csv_value apiKey)"
openai_url="$(csv_value openAiCompatible)"
dashscope_url="$(csv_value dashScope)"
[[ -n "$qwen_key" && "$openai_url" == https://* ]] || { echo "Qwen credential contract failed" >&2; exit 2; }
{
  printf 'QWEN_API_KEY=%q\n' "$qwen_key"
  printf 'QWEN_OPENAI_BASE_URL=%q\n' "$openai_url"
  printf 'QWEN_DASHSCOPE_URL=%q\n' "$dashscope_url"
  if [[ -f "$DIR/deepseek" ]]; then
    deepseek_key="$(tr -d '\r\n' < "$DIR/deepseek")"
    printf 'DEEPSEEK_API_KEY=%q\n' "$deepseek_key"
  fi
} > "$OUTPUT"
chmod 600 "$OUTPUT"
echo "PASS: temporary secret environment materialized at a redacted path"
