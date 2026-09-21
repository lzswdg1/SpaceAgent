#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/export-public-source.sh [--ref <git-ref>] --directory <absolute-path>

Creates a public source snapshot from committed Git objects only. The destination
must be an absolute, non-existing path outside the source repository.
USAGE
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(git -C "$script_dir/.." rev-parse --show-toplevel 2>/dev/null)" || \
  die "the export script must run from a Git worktree"
repo_root="$(CDPATH= cd -- "$repo_root" && pwd -P)"

source_ref=HEAD
target_input=

while (($# > 0)); do
  case "$1" in
    --ref)
      (($# >= 2)) || die "--ref requires a value"
      source_ref=$2
      shift 2
      ;;
    --directory)
      (($# >= 2)) || die "--directory requires a value"
      [[ -z "$target_input" ]] || die "--directory may be specified only once"
      target_input=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

[[ -n "$target_input" ]] || {
  usage >&2
  die "an explicit output target is required"
}

case "$target_input" in
  /*) ;;
  *) die "the output target must be an absolute path" ;;
esac

target_parent_input="$(dirname -- "$target_input")"
target_name="$(basename -- "$target_input")"
[[ "$target_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || \
  die "the output name may contain only letters, numbers, dot, underscore and dash"
[[ -d "$target_parent_input" ]] || die "the output parent directory does not exist"
target_parent="$(CDPATH= cd -- "$target_parent_input" && pwd -P)"
target="$target_parent/$target_name"

case "$target" in
  "$repo_root"|"$repo_root"/*) die "the output target must be outside the source repository" ;;
esac
[[ ! -e "$target" && ! -L "$target" ]] || die "the output target already exists"

if [[ -n "$(git -C "$repo_root" status --porcelain=v1 --untracked-files=no --ignore-submodules=none)" ]]; then
  die "tracked worktree changes are present; commit or restore them before exporting"
fi

source_sha="$(git -C "$repo_root" rev-parse --verify --end-of-options "${source_ref}^{commit}" 2>/dev/null)" || \
  die "Git ref does not resolve to a commit: $source_ref"

# Keep this list fixed and non-secret: it is also written to the public manifest.
excluded_directories=(
  copy/
  .run/
  testapikey/
  .aws/
  .ssh/
  backup/
  backups/
  cert/
  certs/
  certificate/
  certificates/
  secret/
  secrets/
)
excluded_root_directories=(
  output/
)
excluded_generated_patterns=(
  '**/.direnv/'
  '**/.idea/'
  '**/.vscode/'
  '**/.kiro/'
  '**/.m2/'
  '**/.gomodcache/'
  '**/node_modules/'
  '**/dist/'
  '**/target/'
  '**/coverage/'
  '**/__pycache__/'
)
excluded_file_patterns=(
  '.env and non-example .env.* files'
  '*.env, *.env.local and .envrc except *.example templates'
  '.npmrc, .pypirc and .netrc'
  '*.pem, *.key, *.p12, *.pfx, *.jks, *.keystore, *.kdbx, *.crt and *.cer'
  '*.dump, *.backup, *.bak, *.sql.gz, *.tar, *.tar.gz and *.tgz'
  '*.sqlite, *.sqlite3 and *.db'
  'credentials.json, *-credentials.json, service-account*.json, id_rsa and id_ed25519'
)

if git -C "$repo_root" ls-tree -r --full-tree "$source_sha" | \
  awk '$1 == "160000" { found=1 } END { exit found ? 0 : 1 }'; then
  die "the source commit contains a Git submodule; vendor and review it before public export"
fi

stage_dir="$(mktemp -d "$target_parent/.spaceagent-public-export.XXXXXX")"
stage_content="$stage_dir/content"
mkdir "$stage_content"
target_reserved=false

cleanup() {
  rm -rf -- "$stage_dir"
  if [[ "$target_reserved" == true ]]; then
    rm -rf -- "$target"
  fi
}
trap cleanup EXIT HUP INT TERM

archive_pathspecs=(.)
for excluded in "${excluded_root_directories[@]}"; do
  excluded=${excluded%/}
  archive_pathspecs+=(":(exclude)$excluded" ":(exclude)$excluded/**")
done
for excluded in "${excluded_directories[@]}"; do
  excluded=${excluded%/}
  archive_pathspecs+=(":(exclude)$excluded" ":(exclude)$excluded/**")
  archive_pathspecs+=(":(exclude,glob)**/$excluded" ":(exclude,glob)**/$excluded/**")
done
for excluded in "${excluded_generated_patterns[@]}"; do
  excluded=${excluded#\*\*/}
  excluded=${excluded%/}
  archive_pathspecs+=(":(exclude)$excluded" ":(exclude)$excluded/**")
  archive_pathspecs+=(":(exclude,glob)**/$excluded" ":(exclude,glob)**/$excluded/**")
done

git -C "$repo_root" archive --format=tar "$source_sha" -- "${archive_pathspecs[@]}" | \
  tar -xf - -C "$stage_content"

# Environment, certificate and backup names are removed after extraction so that
# tracked public *.example templates remain available.
find "$stage_content" -type f \
  \( -name '.env' -o -name '.env.*' -o -name '*.env' -o -name '*.env.*' \
     -o -name '*.env.local' \
     -o -name '.envrc' -o -name '.npmrc' -o -name '.pypirc' -o -name '.netrc' \) \
  ! -name '*.example' -delete
find "$stage_content" -type f \
  \( -name '*.pem' -o -name '*.key' -o -name '*.p12' -o -name '*.pfx' \
     -o -name '*.jks' -o -name '*.keystore' -o -name '*.kdbx' \
     -o -name '*.crt' -o -name '*.cer' \
     -o -name '*.dump' -o -name '*.backup' -o -name '*.bak' -o -name '*.sql.gz' \
     -o -name '*.tar' -o -name '*.tar.gz' -o -name '*.tgz' \
     -o -name '*.sqlite' -o -name '*.sqlite3' -o -name '*.db' \
     -o -name 'credentials.json' -o -name '*-credentials.json' \
     -o -name 'service-account*.json' \
     -o -name 'id_rsa' -o -name 'id_ed25519' \) \
  ! -name '*.example' -delete

# A Git archive does not dereference symlinks, but publishing absolute or parent-traversing
# links can still make a consumer read unintended host files. The current repository needs no
# source symlinks, so fail closed instead of trying to infer safe link semantics.
if find "$stage_content" -type l -print -quit | grep -q .; then
  die "the source commit contains a symlink; replace it with reviewed source before public export"
fi

[[ -f "$stage_content/ASSET-PROVENANCE.md" ]] || \
  die "the source commit is missing ASSET-PROVENANCE.md"

manifest="$stage_content/PUBLIC-SOURCE-MANIFEST.txt"
[[ ! -e "$manifest" && ! -L "$manifest" ]] || \
  die "the source commit already contains the reserved public manifest path"
{
  printf 'SpaceAgent public source snapshot\n'
  printf 'format_version=1\n'
  printf 'tracked_files_only=true\n'
  printf 'git_history_included=false\n'
  printf 'private_source_commit_embedded=false\n'
  printf 'asset_provenance_documented=true\n'
  printf 'blocked_new_repository_url=false\n'
  printf 'public_repository=https://github.com/lzswdg1/SpaceAgent\n'
  printf '\n[excluded_directories]\n'
  printf '%s\n' "${excluded_root_directories[@]}"
  printf '%s\n' "${excluded_directories[@]}"
  printf '%s\n' "${excluded_generated_patterns[@]}"
  printf '\n[excluded_file_patterns]\n'
  printf '%s\n' "${excluded_file_patterns[@]}"
} > "$manifest"

# mkdir is the no-overwrite boundary; copying starts only after it succeeds. Archive creation is
# deliberately left to the future public repository so local owner/group and gzip timestamps do
# not leak through this privacy boundary.
mkdir "$target" || die "could not reserve the output directory"
target_reserved=true
tar -cf - -C "$stage_content" . | tar -xf - -C "$target" \
  || die "could not copy the reviewed snapshot into the reserved output directory"
target_reserved=false
printf 'Public source directory created: %s\n' "$target"
