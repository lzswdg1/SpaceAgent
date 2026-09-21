#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

dockerfiles=(
  apps/platform-server/Dockerfile
  apps/platform-admin-server/Dockerfile
  apps/web/Dockerfile
  apps/admin-web/Dockerfile
  workers/sandbox-worker/Dockerfile
)

for dockerfile in "${dockerfiles[@]}"; do
  docker buildx build --check --file "$dockerfile" .
done

printf '%s\n' 'PASS: five production Dockerfiles passed BuildKit checks'
