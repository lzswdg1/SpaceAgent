#!/usr/bin/env bash
set -euo pipefail

DIR="${1:-}"
[[ -d "$DIR" ]] || { echo "BLOCKED: credential directory not found" >&2; exit 2; }
qwen="$(find "$DIR" -maxdepth 1 -type f -name '*.csv' -print -quit)"
deepseek="$DIR/deepseek"
[[ -n "$qwen" && -f "$qwen" ]] || { echo "BLOCKED: Qwen CSV not found" >&2; exit 2; }

status=0
for file in "$qwen" "$deepseek"; do
  [[ -f "$file" ]] || continue
  mode="$(stat -f '%Lp' "$file")"
  [[ "$mode" == "600" ]] || { echo "BLOCKED: credential file mode must be 600: $(basename "$file")"; status=1; }
done

for label in apiKey openAiCompatible dashScope; do
  count="$(awk -F',' -v wanted="$label" '{gsub(/^﻿/,"",$1); if($1==wanted && length($2)>0) found++} END{print found+0}' "$qwen")"
  [[ "$count" == "1" ]] || { echo "BLOCKED: Qwen CSV label missing or duplicated: $label"; status=1; }
done
url_ok="$(awk -F',' '{gsub(/^﻿/,"",$1); if($1=="openAiCompatible" && $2 ~ /^https:\/\//) ok=1} END{print ok+0}' "$qwen")"
[[ "$url_ok" == "1" ]] || { echo "BLOCKED: Qwen OpenAI-compatible URL is not HTTPS"; status=1; }
if [[ -f "$deepseek" ]]; then
  lines="$(awk 'NF{count++} END{print count+0}' "$deepseek")"
  [[ "$lines" == "1" ]] || { echo "BLOCKED_OPTIONAL: DeepSeek file must contain one non-empty line"; }
fi

[[ "$status" == "0" ]] || exit 2
echo "PASS: credential metadata is usable; values remain redacted"
