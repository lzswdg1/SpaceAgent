#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
release_env="${1:-}"; settings_env="${2:-}"; action="${3:-config}"
[[ -f "$release_env" && -f "$settings_env" ]] || { echo 'Usage: deploy-low-resource.sh <release.env> <low-resource.env> [config|pull|up|status]' >&2; exit 2; }
release_env="$(cd "$(dirname "$release_env")" && pwd)/$(basename "$release_env")"
settings_env="$(cd "$(dirname "$settings_env")" && pwd)/$(basename "$settings_env")"
case "$action" in config|pull|up|status) ;; *) echo 'Unsupported action; this wrapper never builds, deletes or tears down data.' >&2; exit 2 ;; esac
if [[ "$action" == up || "$action" == pull ]]; then
  for secret_path in "$release_env" "$settings_env"; do
    if [[ "$(uname -s)" == Darwin ]]; then secret_mode="$(stat -f '%Lp' "$secret_path")"; else secret_mode="$(stat -c '%a' "$secret_path")"; fi
    [[ "$secret_mode" == 600 ]] || { echo 'Deployment environment files must be mode0600' >&2; exit 2; }
  done
fi
cd "$task_root"
# Do not source secret files into shell code or leak an inherited heavy COMPOSE_PROFILES selection.
export COMPOSE_PROFILES=
compose=(docker compose --env-file "$release_env" --env-file "$settings_env" -f docker-compose.yml -f docker-compose.images.yml -f docker-compose.release.yml -f docker-compose.low-resource.yml --profile web --profile admin --profile sandbox)
if [[ -n "${LOW_RESOURCE_TLS_OVERLAY:-}" ]]; then
  [[ -f "$LOW_RESOURCE_TLS_OVERLAY" ]] || { echo 'Operator TLS overlay missing' >&2; exit 2; }
  compose+=(-f "$LOW_RESOURCE_TLS_OVERLAY")
fi
if [[ "$action" == config || "$action" == status ]]; then
  summary="$("${compose[@]}" config --format json | python3 scripts/check-low-resource-plan.py)"
else
  summary="$("${compose[@]}" config --format json | python3 scripts/check-low-resource-plan.py --runtime)"
fi
printf '%s\n' "$summary"
case "$action" in
  config) exit 0 ;;
  status) "${compose[@]}" ps ;;
  pull) "${compose[@]}" pull ;;
  up)
    # Host resource proof is required here, not fabricated from a config-only check.
    read -r host_ram host_cpus < <(docker info --format '{{.MemTotal}} {{.NCPU}}')
    needed_ram="$(printf '%s' "$summary" | python3 -c 'import json,sys; print(json.load(sys.stdin)["peakBudgetMiB"]*1024*1024)')"
    [[ "$host_ram" =~ ^[0-9]+$ && "$host_ram" -ge "$needed_ram" && "$host_cpus" =~ ^[0-9]+$ && "$host_cpus" -ge 2 ]] || { echo 'Insufficient host RAM/CPU for the declared low-resource budget' >&2; exit 2; }
    "${compose[@]}" up -d --no-build --pull never ;;
esac
