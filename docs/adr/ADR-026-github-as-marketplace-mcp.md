# ADR-026: GitHub Is a Marketplace MCP Capability

> M38-PR2 completion: official Remote MCP is now exclusive; the native GitHub OAuth/API
> compatibility path described historically below has been removed.

- Status: Accepted / M26 ownership boundary retained / official remote MCP compatibility implemented by ADR-031
- Date: 2026-08-23

GitHub is not a permanently hard-coded Project subsystem in the final product. A user
installs GitHub MCP from the platform Marketplace, then either authorizes their GitHub
account and selects a repository, or submits a public GitHub URL for MCP discovery.

Tooling owns Marketplace metadata, installation, encrypted connection/auth references,
MCP transport/tool discovery, permission and ToolExecutionLedger binding. Project owns the
resulting SourceRepository and Workspace. Java validates and persists authoritative results;
MCP cannot write platform tables or bypass the ledger.

The M19 Native GitHub OAuth/API adapter remains compatibility/rollback-only until MCP proves
public/private import, OAuth replay protection, recovery, redaction and Workspace parity.

M26-PR1 implements the durable catalog, scoped installation, encrypted Connection/AuthRef,
role policy, HTTP API and Organization cleanup integration. M26-PR2 implements the official
SDK Streamable HTTP transport, public-endpoint policy, encrypted one-use OAuth state,
account binding, repository listing and strict public URL discovery. Ledgered Project import
is implemented in M26-PR3 with hashed idempotency, fenced Claim/UNKNOWN semantics and opaque
Connection/Invocation provenance on the Project SourceRepository. Private repository managed
Workspace materialization is implemented in M26-PR4 using an encrypted, expiring,
consumed/redacted Tooling grant and a Project-side in-process credential lease. The parity
gate passes without a Project credential table or HTTP secret contract. Native GitHub stays
disabled-by-default compatibility/rollback code and is not the target product path.

## Production acceptance amendment (2026-08-24)

Real E2E preparation found that the internal M26 facade is not directly compatible with
GitHub's mature official remote MCP. GitHub documents `https://api.githubcopilot.com/mcp/`
as its hosted endpoint and requires the MCP host to implement OAuth 2.1 and obtain a token;
the remote server does not implement authentication on the host's behalf. It also does not
expose SpaceAgent-specific `github_begin_oauth`, `github_complete_oauth`, or
`github_prepare_checkout` tools. M26's fake/custom-server tests therefore prove the internal
ledger and secret boundary, not official-server interoperability.

M31/ADR-031 now keeps this ADR's ownership and redaction invariants while implementing
metadata-discovered host OAuth 2.1, official repository-tool mapping and a Java-owned
ephemeral checkout authorization adapter. A supplied account password is not an integration
credential, and Native GitHub or PAT is not selected silently as the target path.

Official references:

- <https://github.com/github/github-mcp-server>
- <https://github.com/github/github-mcp-server/blob/main/docs/host-integration.md>
