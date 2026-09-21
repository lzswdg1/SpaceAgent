#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export DB_PASSWORD='Ci-Main-Database-Password-2026!'
export JWT_SECRET='ci-tenant-jwt-release-secret-32-characters-long'
export INTERNAL_SERVICE_TOKEN='ci-internal-release-token-32-characters-long'
export IDENTITY_ACTIVITY_HASH_KEY='ci-activity-hash-release-key-32-characters-long'
export SYSTEM_ADMIN_JWT_SECRET='ci-system-admin-jwt-release-32-characters-long'
export MODEL_PROVIDER_ENCRYPTION_KEY='ci-provider-encryption-key-32-characters-long'
export MCP_CONNECTION_ENCRYPTION_KEY='ci-mcp-encryption-key-release-32-characters-long'
export AI_EMBEDDING_API_KEY='ci-provider-embedding-key'
export SPACEAGENT_RELEASE_VERSION='1.0.0-ci'
export PLATFORM_RELEASE_MODE='trusted-beta'
export PLATFORM_TRUSTED_CODE_ONLY='true'
export PLATFORM_PUBLIC_UNTRUSTED_CODE_ENABLED='false'
export PLATFORM_ALLOW_INSECURE_LOCAL='false'
export PLATFORM_BIND_ADDRESS='127.0.0.1'
export ADMIN_DB_PASSWORD='Ci-Admin-Database-Password-2026!'
export ADMIN_JWT_SECRET='ci-admin-jwt-release-secret-32-characters-long'
export ADMIN_COOKIE_SECURE='true'
export ADMIN_LOGIN='ci-platform-administrator'
export ADMIN_PASSWORD='Strong-Ci-Admin-Password-2026!'
export ADMIN_BIND_ADDRESS='127.0.0.1'
export ADMIN_WEB_BIND_ADDRESS='127.0.0.1'
export ADMIN_PLATFORM_CLIENT_BASE_URL='https://10.0.0.5'
export SYSTEM_ADMIN_ALLOW_INSECURE_LOCAL='false'
export PLATFORM_SERVER_IMAGE='registry.invalid/spaceagent/platform-server:ci'
export PLATFORM_ADMIN_SERVER_IMAGE='registry.invalid/spaceagent/platform-admin-server:ci'
export WEB_IMAGE='registry.invalid/spaceagent/web:ci'
export ADMIN_WEB_IMAGE='registry.invalid/spaceagent/admin-web:ci'
export SANDBOX_WORKER_IMAGE='registry.invalid/spaceagent/sandbox-worker:ci'
export MILVUS_BOOTSTRAP_PASSWORD='Ci-Milvus-Password-2026!'

docker compose --profile '*' -f docker-compose.yml config --quiet
docker compose --profile '*' -f docker-compose.yml -f docker-compose.release.yml config --quiet
docker compose --profile web --profile admin --profile sandbox \
  -f docker-compose.yml -f docker-compose.images.yml -f docker-compose.release.yml \
  -f docker-compose.low-resource.yml config --quiet
docker compose -f docker-compose.rag.yml config --quiet
docker compose -f docker-compose.parser.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.parser.yml \
  -f docker-compose.parser-platform.yml config --quiet
docker compose --profile knowledge-worker -f docker-compose.yml -f docker-compose.rag.yml \
  -f docker-compose.parser.yml -f docker-compose.parser-platform.yml \
  -f docker-compose.knowledge-worker.yml config --quiet

printf '%s\n' 'PASS: supported Compose configurations resolve with redacted CI fixtures'
