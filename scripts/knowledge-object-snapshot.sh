#!/usr/bin/env bash
# Explicit offline object snapshot only. Database and model-cipher keys are backed up separately.
set -euo pipefail
umask 077
operation="${1:-}"; source_path="${2:-}"; destination="${3:-}"
fail(){ echo "FAIL: $*" >&2; exit 1; }
[[ "$source_path" == /* && "$destination" == /* ]] || fail 'absolute explicit source and destination are required'
[[ "$source_path" != / && "$destination" != / && ! -e "$destination" ]] || fail 'destination must not exist'
case "$operation" in
  backup)
    [[ ! -e "$destination.sha256" ]] || fail 'checksum sidecar must not exist'
    [[ -d "$source_path/knowledge-index" && ! -L "$source_path" ]] || fail 'expected Knowledge-owned object root'
    [[ "$destination" != "$source_path/"* ]] || fail 'archive must be outside source'
    [[ -z "$(find "$source_path/knowledge-index" -type l -print -quit)" ]] || fail 'symlinks are forbidden'
    tar --exclude='*.partial' -czf "$destination" -C "$source_path" knowledge-index
    shasum -a 256 "$destination" | awk '{print $1}' > "$destination.sha256"
    ;;
  restore)
    [[ -f "$source_path" && -f "$source_path.sha256" ]] || fail 'archive and checksum required'
    [[ "$(shasum -a 256 "$source_path" | awk '{print $1}')" == "$(tr -d '\r\n' < "$source_path.sha256")" ]] || fail 'checksum mismatch'
    # Only archives produced by this tool are accepted. Never extract absolute/traversal names or links.
    tar -tzf "$source_path" | awk '
      !/^knowledge-index(\/[A-Za-z0-9_.:-]+)*\/?$/ {exit 1}
      /(^|\/)\.\.?($|\/)/ {exit 1}
    ' || fail 'unsafe archive paths'
    tar -tvzf "$source_path" | awk 'substr($0,1,1)!="-" && substr($0,1,1)!="d" {exit 1}' || fail 'links and special files are forbidden'
    mkdir -m 700 "$destination"
    tar -xzf "$source_path" -C "$destination"
    chmod -R go-rwx "$destination"
    ;;
  *) fail 'usage: knowledge-object-snapshot.sh backup <absolute-object-root> <new-archive> | restore <archive> <new-root>' ;;
esac
echo 'Knowledge object snapshot completed; no database or active runtime was changed.'
