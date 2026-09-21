# ADR-015: SourceRepository, GitHub, and Local Workspace Bridge

> M38-PR2 supersession: native Project-owned GitHub OAuth/API routing has been removed.
> GitHub account, repository import and private checkout now use ADR-026/ADR-031 official
> remote MCP exclusively. The Local Workspace Bridge decision remains active.

- Status: Accepted
- Date: 2026-08-23
- Scope: M19-PR1

## Context

M18 can execute a durable Project Task, but Project source code still has only an
unpersisted `SourceRepository` reference. Browser, CLI, Desktop, public GitHub, and private
GitHub imports need one authority boundary before M20 provisions worktrees.

## Decision

- Project owns durable SourceRepository and source-control connection metadata in Java/
  PostgreSQL. M19 does not clone, edit, execute, or create a Workspace/worktree.
- GitHub uses the OAuth web application flow with one-time SHA-256 state, ten-minute
  expiry, tenant/user binding, and an allowlisted redirect URI. Java exchanges the code,
  reads the authenticated account, and AES-GCM encrypts the access token.
- The GitHub adapter uses versioned REST requests for authenticated repository listing and
  repository metadata. It is selected only when `platform.source-control.github.mode=http`;
  default `none` fails closed.
- Public repositories may be imported without a connection. Private repositories require
  an active user-owned GitHub connection. No token is exposed in an API view/log/event.
- A Local Workspace Bridge is a user/device/root capability. Server state contains an
  opaque `rootHandle`, display metadata, a hash-only Bridge token, and heartbeat state.
  The CLI keeps the actual absolute path locally in a mode-0600 file.
- HTTP request DTOs contain no local path field. Path-shaped root handles (`/`, `\\`, drive
  prefixes, `..`) are rejected even from a CLI. A browser can reference an existing Bridge
  ID/rootHandle, but that reference cannot make the remote server read a filesystem path.

## External protocol evidence

- GitHub OAuth web application flow and state/code exchange:
  <https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps>
- GitHub authenticated/public repository REST endpoints:
  <https://docs.github.com/en/rest/repos/repos>

## Consequences

- Java remains the sole credential and repository-metadata authority.
- Local paths never cross the client/server boundary in M19.
- M20 may provision isolated Workspaces from these references, but cannot reinterpret an
  opaque root handle as a server path or share one writable worktree across agents.
