#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
exporter="$script_dir/export-public-source.sh"
[[ -x "$exporter" ]] || {
  printf 'ERROR: exporter is not executable: %s\n' "$exporter" >&2
  exit 1
}

test_root="$(mktemp -d /tmp/spaceagent-public-export-test.XXXXXX)"
cleanup() {
  rm -rf -- "$test_root"
}
trap cleanup EXIT HUP INT TERM

fixture="$test_root/repository"
mkdir -p "$fixture/scripts" "$fixture/.agent" "$fixture/docs/archive" \
  "$fixture/copy" "$fixture/output" "$fixture/testapikey" "$fixture/certs" \
  "$fixture/backups" "$fixture/apps/demo/target" "$fixture/apps/demo/secrets" \
  "$fixture/apps/demo/.aws" "$fixture/apps/demo/config" "$fixture/cli/internal/output" \
  "$fixture/cli/.gomodcache/cache/download/example.invalid/module/@v"
cp "$exporter" "$fixture/scripts/export-public-source.sh"
chmod +x "$fixture/scripts/export-public-source.sh"

printf '%s\n' '# fixture' > "$fixture/README.md"
printf '%s\n' '# Fixture asset provenance' > "$fixture/ASSET-PROVENANCE.md"
printf '%s\n' 'handoff' > "$fixture/.agent/CURRENT.md"
printf '%s\n' 'history' > "$fixture/docs/archive/history.md"
printf '%s\n' 'PUBLIC_TEMPLATE=true' > "$fixture/.env.example"
printf '%s\n' 'PUBLIC_RELEASE_TEMPLATE=true' > "$fixture/.env.release.example"
printf '%s\n' 'do not publish' > "$fixture/copy/unlicensed.txt"
printf '%s\n' 'generated' > "$fixture/output/generated.txt"
printf '%s\n' 'credential' > "$fixture/testapikey/provider.txt"
printf '%s\n' 'LOCAL_SECRET=true' > "$fixture/.env.local"
printf '%s\n' 'LOCAL_ENVRC=true' > "$fixture/.envrc"
printf '%s\n' 'certificate' > "$fixture/certs/development.pem"
printf '%s\n' 'credential object' > "$fixture/credentials.json"
printf '%s\n' 'registry credential' > "$fixture/.npmrc"
printf '%s\n' 'password store' > "$fixture/local.kdbx"
printf '%s\n' 'archive' > "$fixture/private.tar.gz"
printf '%s\n' 'database' > "$fixture/backups/database.dump"
printf '%s\n' 'build output' > "$fixture/apps/demo/target/result.bin"
printf '%s\n' 'package output source' > "$fixture/cli/internal/output/print.go"
printf '%s\n' 'generated module cache' > "$fixture/cli/.gomodcache/cache/download/example.invalid/module/@v/cache.lock"
printf '%s\n' 'nested credential' > "$fixture/apps/demo/secrets/token"
printf '%s\n' 'nested cloud credential' > "$fixture/apps/demo/.aws/credentials"
printf '%s\n' 'nested private env' > "$fixture/apps/demo/config/production.env.private"
printf '%s\n' 'NESTED_PUBLIC_TEMPLATE=true' > "$fixture/apps/demo/config/public.env.example"
printf '%s\n' '/output/' '/testapikey/' > "$fixture/.gitignore"

git -C "$fixture" init -q
git -C "$fixture" config user.name 'Public Export Test'
git -C "$fixture" config user.email 'public-export@example.invalid'
git -C "$fixture" add .
git -C "$fixture" add -f output/generated.txt testapikey/provider.txt
git -C "$fixture" commit -qm 'fixture'
source_sha="$(git -C "$fixture" rev-parse HEAD)"

# Ignored and untracked files are allowed and can never enter the Git archive.
printf '%s\n' 'local only' > "$fixture/output/local-only.txt"

directory_target="$test_root/public-directory"
"$fixture/scripts/export-public-source.sh" --ref "$source_sha" --directory "$directory_target"

test -f "$directory_target/README.md"
test -f "$directory_target/ASSET-PROVENANCE.md"
test -f "$directory_target/.agent/CURRENT.md"
test -f "$directory_target/docs/archive/history.md"
test -f "$directory_target/.env.example"
test -f "$directory_target/.env.release.example"
test -f "$directory_target/PUBLIC-SOURCE-MANIFEST.txt"
test ! -e "$directory_target/.git"
test ! -e "$directory_target/copy"
test ! -e "$directory_target/output"
test ! -e "$directory_target/testapikey"
test ! -e "$directory_target/certs"
test ! -e "$directory_target/backups"
test ! -e "$directory_target/apps/demo/target"
test -f "$directory_target/cli/internal/output/print.go"
test ! -e "$directory_target/cli/.gomodcache"
test ! -e "$directory_target/apps/demo/secrets"
test ! -e "$directory_target/apps/demo/.aws"
test ! -e "$directory_target/apps/demo/config/production.env.private"
test -f "$directory_target/apps/demo/config/public.env.example"
test ! -e "$directory_target/.env.local"
test ! -e "$directory_target/.envrc"
test ! -e "$directory_target/credentials.json"
test ! -e "$directory_target/.npmrc"
test ! -e "$directory_target/local.kdbx"
test ! -e "$directory_target/private.tar.gz"
test ! -e "$directory_target/certs/development.pem"
test ! -e "$directory_target/backups/database.dump"
rg -q '^private_source_commit_embedded=false$' "$directory_target/PUBLIC-SOURCE-MANIFEST.txt"
rg -q '^asset_provenance_documented=true$' "$directory_target/PUBLIC-SOURCE-MANIFEST.txt"
rg -q '^blocked_new_repository_url=false$' "$directory_target/PUBLIC-SOURCE-MANIFEST.txt"
rg -q '^public_repository=https://github.com/lzswdg1/SpaceAgent$' "$directory_target/PUBLIC-SOURCE-MANIFEST.txt"
! rg -q "$source_sha" "$directory_target/PUBLIC-SOURCE-MANIFEST.txt"
rg -q '^copy/$' "$directory_target/PUBLIC-SOURCE-MANIFEST.txt"
! rg -q "$test_root|LOCAL_SECRET|credential" "$directory_target/PUBLIC-SOURCE-MANIFEST.txt"

if "$fixture/scripts/export-public-source.sh" --tar "$test_root/public-archive.tar.gz" >/dev/null 2>&1; then
  printf '%s\n' 'ERROR: deprecated tar output was accepted' >&2
  exit 1
fi

if "$fixture/scripts/export-public-source.sh" --directory "$directory_target" >/dev/null 2>&1; then
  printf '%s\n' 'ERROR: existing destination was accepted' >&2
  exit 1
fi
if "$fixture/scripts/export-public-source.sh" --directory "$fixture/inside-repository" >/dev/null 2>&1; then
  printf '%s\n' 'ERROR: repository-internal destination was accepted' >&2
  exit 1
fi
if "$fixture/scripts/export-public-source.sh" --directory relative-output >/dev/null 2>&1; then
  printf '%s\n' 'ERROR: relative destination was accepted' >&2
  exit 1
fi

printf '%s\n' 'dirty' >> "$fixture/README.md"
dirty_target="$test_root/dirty-export"
if "$fixture/scripts/export-public-source.sh" --directory "$dirty_target" >/dev/null 2>&1; then
  printf '%s\n' 'ERROR: dirty tracked worktree was accepted' >&2
  exit 1
fi
test ! -e "$dirty_target"

# Public snapshots reject tracked links rather than publishing host-path references.
git -C "$fixture" restore README.md
ln -s /etc/passwd "$fixture/host-reference"
git -C "$fixture" add host-reference
git -C "$fixture" commit -qm 'add unsafe symlink fixture'
symlink_target="$test_root/symlink-export"
if "$fixture/scripts/export-public-source.sh" --directory "$symlink_target" >/dev/null 2>&1; then
  printf '%s\n' 'ERROR: tracked symlink was accepted' >&2
  exit 1
fi
test ! -e "$symlink_target"

printf '%s\n' 'PASS: public source export is tracked-only, history-free and no-overwrite safe'
