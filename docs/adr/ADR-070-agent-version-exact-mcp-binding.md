# ADR-070: AgentVersion Exact MCP Binding

- Status: Accepted
- Date: 2026-09-08
- Scope: M61-PR1

## Context

Tooling owns MCP Marketplace entries, immutable ServerVersions, Installations, Connections,
encrypted credentials, qualification observations and immutable CapabilitySnapshots. AgentVersion
currently pins Tools/Skills/Knowledge/ModelPool but cannot pin the exact MCP capability that makes
an MCP Tool available. Resolving a mutable current Connection at Run time would silently change an
already-published Agent and make historical execution non-reproducible.

## Decision

- Agent owns an immutable `AgentVersionMcpBinding` aggregate. It records tenant/owner/Agent/
  AgentVersion scope plus opaque Installation, Connection, ServerVersion and CapabilitySnapshot IDs,
  exact Connection revision, snapshot SHA-256, sorted Tool allowlist and an aggregate binding hash.
- Agent stores no endpoint, OAuth material, token, advertised Tool schema/content or qualification
  payload. Tooling remains the only owner of those values and validates references through a public
  Application API before publish/runtime use.
- A binding is append-only for one AgentVersion. Reconfiguration creates new Tooling revisions and a
  later AgentVersion binding; it never rewrites an old binding or a pinned Run.
- U02 adds Agent persistence/FKs. U03 validates same tenant, installed/ACTIVE/qualified exact revision,
  current snapshot and allowlisted Tools, then pins the binding into Runtime. U04 and U05 add bounded
  Resources and Prompts without bypassing Context/Governance/Ledger.
- MCP Tasks are not a supported capability while the official Java SDK lacks a formal API. U06A explicitly
  advertises no `io.modelcontextprotocol/tasks` capability, sends no `tasks/get`, `tasks/update` or
  `tasks/cancel`, and rejects either a remote Tasks capability or Task-shaped result with the stable,
  content-free `MCP_TASKS_UNSUPPORTED` error.
- U06B is `DEFERRED_UPSTREAM / NOT IMPLEMENTED`, not a production-mainline blocker. It may resume only after
  a released official Java SDK provides formal Tasks APIs and testable negotiation, poll, cancel and abnormal
  state behavior, through a separate ADR and dependency-upgrade Work Unit.

## Failure semantics

- Missing, cross-tenant, user-inaccessible, degraded/revoked, stale-revision, changed ServerVersion,
  changed snapshot hash or Tool mismatch fails closed.
- Runtime may use only a previously validated immutable binding. It cannot choose a Connection or
  silently upgrade a snapshot. UNKNOWN MCP effects retain existing Tool Ledger semantics.
- A remote Task never becomes Runtime authority or a Java Continuation. Experimental Task capability/results
  fail closed before content or identifiers enter Context, persistence or a Tool result.

## Consequences

- Java/PostgreSQL remain authoritative; Tooling credentials and protocol state do not move to Agent.
- Java Runtime Continuation continues to manage only platform-owned durable work. No parallel Tasks protocol
  client, copied SDK implementation, SDK fork, persistence model or polling worker is introduced.
- The implemented protocol support matrix is maintained in
  [`MCP-CAPABILITY-SUPPORT.md`](../architecture/MCP-CAPABILITY-SUPPORT.md).
