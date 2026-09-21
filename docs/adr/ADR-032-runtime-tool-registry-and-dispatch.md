# ADR-032: Runtime Tools Use a Java-Owned Registry and Dispatcher

- Status: Accepted / backend implemented in M33-PR1
- Date: 2026-08-25

## Context

Before M33, AgentVersion already persisted enabled Tool IDs and Chat already accepted model
Tool Calls, but the executable catalog contained only `echo`. Inference hard-coded that one
schema and Chat sent every call directly to the Sandbox adapter. Knowledge retrieval,
managed Workspace/Git/Coding operations, MCP Connections and GitHub repository discovery
existed behind separate Java application boundaries but could not be safely selected by an
Agent at runtime.

Putting those effects in TypeScript, Python or the model provider would create a second
authority for tenant scope, Provider/MCP secrets, Git state and recovery. Accepting arbitrary
model-generated names or arguments would also bypass AgentVersion permissions and durable
idempotency.

## Decision

- Tooling owns a code-versioned catalog of 16 executable Tool definitions. Every definition
  carries a Draft 2020-12 JSON Schema plus availability, read-only, network and Workspace
  metadata. Agent create/update/version validation and Inference use that same catalog.
- Runtime owns one dispatcher. It derives user, tenant, Project, Task and pinned AgentVersion
  scope from the AgentRun, validates the model arguments, enforces the enabled Tool set and
  Agent network policy, and calls only public Java application APIs.
- ToolExecutionLedger remains the idempotency source. Matching terminal calls replay before
  requesting another approval. Read failures finish `FAILED`; an indeterminate mutation is
  marked `UNKNOWN` and is never blindly retried. Existing Sandbox and Coding ledgers remain
  authoritative for `echo` and Coding actions.
- New external network effects require Governance `NETWORK_ACCESS`. Document writes require
  `CODING_FILE_MUTATION`; existing Coding Runtime keeps its exact file/command approval hash,
  executable allowlist, Artifact evidence and trusted-code release boundary.
- `web_search` uses an operator-configured SearXNG JSON endpoint and is unavailable when no
  provider is configured. `http_fetch` accepts public HTTPS text formats only, disables
  redirects and bounds time, bytes and extracted characters through the existing DNS/IP SSRF
  policy.
- File, Git and document tools operate only on a READY, writable `MANAGED_GIT` Workspace bound
  to the Run's tenant/Project/Task. Paths are relative, traversal and symlinks are rejected,
  reads/lists/diffs are bounded, and document writes are atomic.
- Apache Tika performs bounded text extraction; Apache POI creates DOCX. Parser libraries stay
  in Project infrastructure and never enter domain/public types.
- Dynamic MCP first resolves an authorized Connection and advertised remote Tool. Its bounded
  schema is validated locally; remote schema references are rejected, and read-only annotation
  changes between discovery and execution fail closed. GitHub search/repository wrappers still
  call the connected Marketplace MCP capability rather than a separate GitHub credential path.
- AgentVersion's existing enabled Tool ID list is sufficient. M33 creates no new durable table
  and keeps Flyway at V1035.

## Consequences

Chat can now execute the backend Tool suite without moving persistence, secrets or effects out
of Java. Deployments must configure SearXNG explicitly to advertise Web Search. M53-PR1 adds the
durable same-Run Chat approval interaction, and M53-PR2 adds read-only postcondition reconciliation
for supported Workspace mutations.
Workspace tools are Project Task-scoped and do not make a browser-local directory accessible
to the server.

Primary dependency/provider references:

- <https://docs.searxng.org/dev/search_api.html>
- <https://github.com/networknt/json-schema-validator/releases>
- <https://tika.apache.org/download.html>
- <https://poi.apache.org/>
