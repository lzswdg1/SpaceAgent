#!/usr/bin/env bash
# Read-only, bounded manifest for pre-inventory objects. Does not access PostgreSQL or infer data permissions.
set -euo pipefail
root="${1:-}"; limit="${2:-1000}"; after="${3:-}"
[[ "$root" == /* && "$root" != / && -d "$root/knowledge-index" && "$limit" =~ ^[0-9]+$ && "$limit" -ge 1 && "$limit" -le 10000 ]] || { echo 'Usage: knowledge-object-inventory.sh <absolute-root> [1..10000]' >&2; exit 1; }
[[ -z "$(find "$root/knowledge-index" -type l -print -quit)" ]] || { echo 'Symlinks forbidden' >&2; exit 1; }
count=0
while IFS= read -r -d '' file; do
  [[ "$count" -lt "$limit" ]] || { echo 'Manifest bounded; process the remaining files in a later inventory pass.' >&2; break; }
  reference="${file#"$root/"}"; owner="${reference#knowledge-index/}"; owner="${owner%%/*}"; digest="${file##*/}"
  [[ -z "$after" || "$reference" > "$after" ]] || continue
  [[ "$owner" =~ ^[A-Za-z0-9_:-][A-Za-z0-9_.:-]{0,35}$ && "$digest" =~ ^[a-f0-9]{64}$ ]] || continue
  actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  [[ "$actual" == "$digest" ]] || { echo 'Object checksum mismatch; inventory stopped.' >&2; exit 1; }
  printf '%s\t%s\n' "$reference" "$owner"
  count=$((count+1))
done < <(find "$root/knowledge-index" -mindepth 2 -maxdepth 2 -type f -print0 | LC_ALL=C sort -z)
