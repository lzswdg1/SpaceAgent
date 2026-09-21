# ADR-031: Official GitHub Remote MCP Uses Host OAuth 2.1

- Status: Accepted / backend implemented in M31-PR1 / real account acceptance requires operator OAuth App credentials
- Date: 2026-08-24

## Context

M26 implemented a safe internal GitHub-as-MCP boundary, but assumed the remote server would
expose SpaceAgent-specific OAuth and checkout tools. GitHub's official hosted MCP instead
requires the client application to act as the OAuth 2.1 host. It advertises protected-resource
metadata through `WWW-Authenticate`; the host discovers the authorization server, performs
Authorization Code + PKCE, stores the resulting token, and sends it as a Bearer token to MCP.
The official server exposes `get_me` and `search_repositories`, not SpaceAgent's custom
`github_begin_oauth`, `github_complete_oauth`, or `github_prepare_checkout` tools.

## Decision

- The Marketplace GitHub entry defaults to `https://api.githubcopilot.com/mcp/` and identifies
  the M31 official host profile. Direct PAT/token configuration is rejected for that profile.
- Tooling follows the advertised `resource_metadata` URL, validates the protected resource,
  authorization server and PKCE S256 metadata through the existing public-HTTPS/SSRF policy,
  and does not hardcode authorization/token endpoints.
- Spring Security OAuth2 Client owns Authorization Code, PKCE, token exchange and refresh
  request construction. SpaceAgent owns tenant/user/connection-bound state and encryption.
- Raw OAuth state is returned once and stored only as SHA-256. The PKCE verifier, redirect URI
  and discovered endpoints are encrypted in the existing OAuth-state row. Callback codes are
  neither persisted nor logged.
- Access/refresh tokens and discovered refresh metadata remain inside the encrypted Tooling
  Connection AuthRef. Refresh uses connection-revision compare-and-set so two replicas cannot
  overwrite a newer token rotation.
- `get_me` maps account identity. Bounded `search_repositories` text JSON maps personal and
  accessible private repositories and exact `owner/name` selection into the existing strict
  repository contract. Import keeps the existing Invocation Ledger Claim/Fencing/Replay/
  UNKNOWN semantics.
- The official server does not return Git credentials. Tooling derives a five-minute encrypted
  checkout grant from the OAuth token, formats Git HTTPS Basic authentication with the bound
  account login, and exposes it only through the existing closeable in-process Project lease.
  Consumption/expiry clears the copied ciphertext exactly as in M26-PR4.
- Non-official custom GitHub MCP endpoints retain the M26 `github_*` facade as a compatibility
  path. M38-PR2 removed Native Project GitHub; no second OAuth/API path can be selected.

## Consequences

V1035 updates only Marketplace endpoint/manifest metadata; no second credential table is
created. The release schema advances to V1035. Operators must register a dedicated GitHub App
or OAuth App and configure client ID, client secret and exact HTTPS or explicit loopback
callback allowlist before browser acceptance. A web username/password is not an integration
credential.

Official references:

- <https://github.com/github/github-mcp-server/blob/main/docs/host-integration.md>
- <https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md>
- <https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html>
