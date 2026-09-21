# ADR-071: Capability-specific UNKNOWN effect verification

- Status: Accepted
- Date: 2026-09-08
- Milestone: M61-PR2

## Context

The ToolExecutionLedger correctly preserves ambiguous mutations as `UNKNOWN`. ADR-054 permits a small set of
Workspace mutations to be reconciled by exact read-only postconditions, but dynamic MCP mutations and Sandbox
commands have no generic trustworthy proof. Retrying the original operation can duplicate an external effect;
letting a client submit a terminal state or raw evidence would turn reconciliation into an arbitrary ledger editor.

## Decision

- Tooling owns a code-local, versioned `LocalToolEffectVerifierRegistry`. Only exact effect/tool bindings whose
  immutable definitions are both `LOCAL_REVIEWED` and read-only may be registered. Remote MCP metadata, Registry
  content, models and clients cannot register or select a verifier.
- A definition pins verifier ID/version, effect kind, exact platform Tool name, evidence schema version, UTF-8
  input/evidence budgets and timeout. Duplicate effect/tool bindings fail startup construction.
- Verification requests are assembled from authoritative Java state. They bind tenant, owner, AgentRun, RunStep,
  ToolExecution, ToolCall, persisted Ledger input hash/revision, bounded canonical input and its digest.
- MCP scope additionally binds exact Connection ID/revision, CapabilitySnapshot ID/hash and remote Tool name.
  Sandbox command scope binds exact Workspace, command digest and allowlisted executable. Neither scope contains
  endpoints, credentials, arbitrary host paths or client-selected evidence.
- Verifiers return only `PROVEN_APPLIED`, `PROVEN_NOT_APPLIED` or `INCONCLUSIVE` plus versioned hash-only evidence,
  byte count, safe code and timestamps. They do not return `ToolExecutionStatus`, invoke the mutation, authorize a
  retry or resume Runtime. Definition/request/evidence mismatches fail closed.
- The public Application command contains only tenant/owner/Run/ToolCall/expected Ledger revision and a bounded
  reason. It contains no verifier ID, verdict, resolution, result or evidence field.

## Ownership and failure semantics

Tooling owns verifier policy and later Ledger CAS. MCP owner APIs and OCI Sandbox may provide bounded read-only
observations through adapters; Runtime only coordinates the request and later same-Run Continuation. A timeout,
unsupported binding, stale revision, scope/hash mismatch, malformed evidence or verifier exception leaves the
Ledger `UNKNOWN` and never redispatches the mutation.

U01 defines the framework-independent contract and ADR only. U02 adds exact MCP adapters, U03 adds allowlisted
Sandbox command adapters, U04 adds Ledger CAS/Continuation, and U05 adds HTTP/security/final gates.

## Implemented MCP verifier subset

M61-PR2-U02 registers one exact production verifier for GitHub official MCP `issue_write(method=update)` when the
mutation contains only issue `title`, `body` and/or `state`. It revalidates the current ACTIVE Connection revision,
CapabilitySnapshot ID/hash and advertised `issue_write`/read-only `issue_read` annotations, authorizes that exact
Connection, then calls only `issue_read(method=get)`. Issue number, canonical repository URL and every requested
field must match. Create and composite assignee/label/milestone/field mutations remain unsupported because a single
issue read cannot prove their complete effect. The verifier returns hash-only proof and never invokes `issue_write`.

M61-PR2-U03 registers one exact Sandbox command verifier for the `workspace-run_command` Ledger effect produced
by model Tool `coding_run_command`, limited to local `git config user.name|user.email <value>`.
The scope includes Workspace ID, opaque `workspaces/<id>` reference, Task reference, executable and command digest.
Verification invokes only `git config --get <key>` through `workspace-read-command-postcondition`, which forces a
read-only, network-disabled OCI mount. Push, global config, other keys/executables and changed digests remain
unsupported; timeout or worker ambiguity is inconclusive and never redispatches the original command.

M61-PR2-U04 implements the owner boundary. Runtime derives MCP scope from the Run-pinned AgentVersion binding and
command scope from the Run-bound Workspace/Task plus persisted Ledger input. Tooling reconstructs the verifier
request from its UNKNOWN Ledger row and maps only conclusive proof through the existing input-hash/revision CAS:
applied becomes `SUCCEEDED`, not-applied becomes `FAILED`, and inconclusive/stale remains `UNKNOWN`. Hash-only
evidence uses the existing reconciliation JSON; terminal replay lets the existing Chat lease/checkpoint flow
continue the same Run without another effect dispatch.

## Consequences

Capability-specific proof can grow without becoming a generic exactly-once claim or a plugin execution surface.
No new migration or persistence authority is introduced. HTTP/frontend and live external acceptance remain
outside U01-U04.

M61-PR2-U05 keeps the existing owner-scoped Chat reconciliation route as the only public entry. Its request has
only expected Ledger revision and a bounded reason; tenant/owner/Run/Scope/verifier/verdict/evidence are derived
inside Java. Cross-tenant access is not found, client-supplied terminal/verifier/evidence fields have no authority,
and the Chat response exposes no verifier evidence. Unsupported bindings return a stable safe code and remain
UNKNOWN. The registry and definitions are code-local and hold no tenant state, so existing Tool Ledger and Runtime
cleanup already remove all durable reconciliation evidence; no new cleanup participant or migration is required.
