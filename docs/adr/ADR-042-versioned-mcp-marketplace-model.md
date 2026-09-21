# ADR-042: Versioned MCP Marketplace Model

- Status: Accepted / implemented in M50-PR1
- Date: 2026-09-03

## Context

The M26 Marketplace stores one mutable row per catalog entry. Installation points only at that
entry, while transport, auth type and manifest are current-row fields. That is sufficient for the
two built-in GitHub/Custom entries, but it cannot safely ingest an external registry or explain
which manifest an existing Organization approved. Updating a catalog row could silently change the
endpoint or declared capabilities used by every existing Installation.

M50 must establish provenance and version pinning before Registry synchronization, generic OAuth,
connection qualification or AgentVersion MCP bindings. The Official MCP Registry is an external
metadata source, not SpaceAgent business authority, and its availability must never be required for
an already-installed server to run.

## Decision

Tooling and PostgreSQL remain the sole Marketplace authority. V1039 keeps
`platform_mcp_marketplace_entries` as stable server identity and rolling-compatibility projection,
then adds:

- `platform_mcp_publishers` for namespace, source and verification evidence;
- `platform_mcp_server_versions` for immutable version/manifest/digest/auth/provenance evidence;
- `platform_mcp_server_transports` for ordered, version-owned remote transport metadata;
- `platform_mcp_installations.server_version_id` as a required pinned reference.

Composite foreign keys prove that an Entry's current Version and an Installation's pinned Version
belong to the same Entry. Existing GitHub and Custom entries become platform-curated built-in
publishers with an approved `1.0.0` snapshot. Existing Installations are backfilled to the Entry's
current Version without changing Connection rows, encrypted AuthRefs, OAuth state, Invocation
evidence or checkout grants.

Catalog reads retain the old transport/auth/default-endpoint/manifest fields for rolling clients
and add Publisher, source, trust, lifecycle, current Version and manifest digest. Version history
and exact Version/Transport reads are bounded. New Installations may request an approved Version or
omit it to pin the current one. Reinstalling never silently changes an existing pin; Version upgrade
requires a later explicit workflow.

## Consequences

- Catalog metadata can evolve without rewriting the manifest approved by an existing Installation.
- V1039 becomes the Trusted Beta release-readiness schema; all previous Flyway migrations remain
  immutable.
- GitHub host OAuth, repository import, private checkout, Runtime/Governance/Ledgers and encrypted
  credentials retain their existing behavior.
- External Registry synchronization, generic OAuth, connection qualification/health, multiple
  account Connections, AgentVersion bindings and MCP Resources/Prompts/Tasks are deliberately later
  milestones.
- Remote Streamable HTTP remains the only executable transport. Package/STDIO metadata must not
  cause a process to start inside `platform-server`.
