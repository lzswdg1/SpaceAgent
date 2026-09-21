# MCP Capability Support

> Updated: 2026-09-08
> Authority: Java Tooling through the official MCP Java SDK

## Supported production subset

| Capability | Status | Boundary |
| --- | --- | --- |
| Initialize and Tools | SUPPORTED | Qualified ACTIVE Connection, exact revision/snapshot and Agent Tool allowlist |
| Resources list/read | SUPPORTED_BOUNDED | Read-only; URI, MIME, metadata, byte and credential-shaped content checks |
| Prompts list/get | SUPPORTED_BOUNDED | Read-only; advertised argument schema, required values, roles, byte and Secret checks |
| MCP Tasks | DEFERRED_UPSTREAM / NOT IMPLEMENTED | No capability advertisement, Task methods, remote authority or Runtime mapping |

Tools, Resources and Prompts use the existing official SDK Streamable HTTP transport and encrypted
Tooling-owned Connection authorization. Agent current configuration stores exact opaque binding evidence and each Run snapshots it. Runtime cannot
select a different Connection or CapabilitySnapshot and no MCP credential enters Agent, Context or a Run.

## Tasks fail-closed compatibility

- The client sends an explicit capability object that does not contain `tasks` or
  `io.modelcontextprotocol/tasks`.
- The official SDK JSON boundary inspects inbound protocol envelopes before typed conversion. A top-level Tasks
  capability, Tasks extension capability, core Task result or extension Task result becomes the stable
  `MCP_TASKS_UNSUPPORTED` business error without exposing remote status text, Task ID or result content.
- SpaceAgent never sends `tasks/get`, `tasks/update` or `tasks/cancel` and has no remote Task poller, cancellation
  worker, table, checkpoint or recovery state.
- Remote Tasks cannot create, resume, cancel or complete a Java Runtime Continuation. Continuations remain
  platform-owned PostgreSQL state for SpaceAgent workflows only.
- A server that offers ordinary Tools, Resources and Prompts without Tasks continues to work unchanged.

## Deferred upstream integration

`M61-PR1-U06B` is deliberately outside the current production mainline and is not implemented. Re-entry requires
all of the following:

1. The official Java SDK publishes a stable release with formal MCP Tasks APIs.
2. The SDK provides deterministic tests for client capability negotiation, polling, cancellation and abnormal
   or ambiguous Task states.
3. SpaceAgent accepts a separate ADR and dependency-upgrade Work Unit that preserves Java/PostgreSQL authority,
   Tool Ledger UNKNOWN semantics and rollback compatibility.

Raw Tasks JSON-RPC, copied foreign-language SDK code, a fork or modification of the official Java SDK, and a
second task state machine are prohibited.

## Capability-specific UNKNOWN verification

- `github.issue_write.update.postcondition/v1` supports only GitHub official MCP
  `issue_write(method=update)` for title/body/state and observes through exact read-only `issue_read(method=get)`.
- `git-config-postcondition/v1` supports only the `workspace-run_command` Ledger effect for local
  `git config user.name|user.email`; it observes through `git config --get` in a read-only, network-disabled OCI
  Workspace.
- Runtime derives exact MCP binding or Workspace/Task/command scope. Tooling reconstructs the canonical input from
  its UNKNOWN Ledger and owns the input-hash/revision CAS. Clients cannot select verifier, verdict or evidence.
- Conclusive applied/not-applied proof becomes SUCCEEDED/FAILED without redispatch. Unsupported, stale, malformed,
  timed-out or inconclusive proof remains UNKNOWN. Persisted evidence contains hashes, versions, byte counts and
  safe codes only.
- The verifier registry is code-local and stateless. Existing Tool Ledger and Runtime tenant cleanup remove all
  durable evidence; no separate verifier cleanup state exists.
