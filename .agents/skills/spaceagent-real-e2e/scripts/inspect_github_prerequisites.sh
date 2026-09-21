#!/usr/bin/env bash
set -euo pipefail

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="$(cd "$SKILL_DIR/../../.." && pwd)"
ENDPOINT="${REAL_GITHUB_MCP_ENDPOINT:-https://api.githubcopilot.com/mcp/}"
CLIENT_ID="${PLATFORM_GITHUB_MCP_CLIENT_ID:-${GITHUB_MCP_CLIENT_ID:-}}"
CLIENT_SECRET="${PLATFORM_GITHUB_MCP_CLIENT_SECRET:-${GITHUB_MCP_CLIENT_SECRET:-}}"
REDIRECTS="${PLATFORM_GITHUB_MCP_ALLOWED_REDIRECT_URIS:-${GITHUB_MCP_ALLOWED_REDIRECT_URIS:-}}"

fail() { echo "FAIL: $*" >&2; exit 1; }

for path in \
  apps/platform-server/src/main/java/com/spaceagent/platform/tooling/infrastructure/OfficialGithubMcpOAuthMetadataResolver.java \
  apps/platform-server/src/main/java/com/spaceagent/platform/tooling/infrastructure/SpringSecurityGithubMcpHostOAuthGateway.java \
  apps/platform-server/src/main/java/com/spaceagent/platform/tooling/application/GithubOfficialMcpAdapter.java \
  apps/platform-server/src/main/resources/db/platform-server/V1035__official_github_remote_mcp.sql; do
  [[ -f "$ROOT_DIR/$path" ]] || fail "M31 GitHub MCP host compatibility artifact is missing"
done

if [[ "$ENDPOINT" != https://* ]]; then
  fail "GitHub MCP endpoint must use HTTPS"
fi

if [[ "$ENDPOINT" == "https://api.githubcopilot.com/mcp" \
      || "$ENDPOINT" == "https://api.githubcopilot.com/mcp/" ]]; then
  if [[ -z "$CLIENT_ID" || -z "$CLIENT_SECRET" || -z "$REDIRECTS" ]]; then
    echo "BLOCKED_GITHUB_MCP_OAUTH_APP_CONFIGURATION: M31 host support is ready; configure a dedicated client ID, client secret and exact callback allowlist"
  else
    echo "READY_OFFICIAL_GITHUB_MCP_OAUTH: host metadata discovery, PKCE, tool mapping and checkout adapter are present; begin platform OAuth"
  fi
  exit 0
fi

SOURCE="$ROOT_DIR/apps/platform-server/src/main/java/com/spaceagent/platform/tooling/application/GithubMcpApplicationService.java"
for tool in github_begin_oauth github_complete_oauth; do
  grep -Fq "\"$tool\"" "$SOURCE" \
    || fail "custom GitHub MCP compatibility tool contract is missing"
done
echo "READY_CUSTOM_GITHUB_MCP_CONTRACT_UNVERIFIED: custom endpoint supplied; remote tool discovery and OAuth are still required"
