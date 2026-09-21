# Authoritative business flows

## Registration and Organization

```text
register -> User/Credential -> default Organization -> OWNER membership -> session tokens
join/switch -> membership validation -> Organization-scoped token
last member leaves -> DELETING -> durable cleanup Job/Steps -> ordered module purge -> DELETED
```

Identity owns lifecycle and finalization; module cleanup participants delete only their owned data.

## Provider, ModelPool and Agent

```text
Provider secret -> Java encryption -> connection/health test -> ProviderModel
ProviderModel -> ModelPool member/priority/weight/price -> activate routing snapshot
AgentDefinition -> one mutable current ModelPool/tool/prompt policy (create/save immediately active)
Organization non-owner edit -> temporary Agent proposal -> current OWNER Governance decision
-> approved current-config CAS | rejected/stale payload erasure
Skill definition -> immutable SkillVersion draft/publish -> exact Agent current-config binding
AgentRun admission -> immutable Run configuration snapshot -> exact SkillVersion context + ID/hash RunStep evidence
-> routing snapshot
```

UNKNOWN Provider outcomes retain ledger/budget reservation and never trigger blind fallback.
Only the creator or current Organization OWNER takes the direct Agent save branch. Pending member
proposals are not configuration and cannot be captured by Run admission.

## Chat

```text
request -> tenant/owner Conversation -> reserve Message pair
-> source-Message-idempotent CHAT Root Task -> focus Conversation
-> Runtime AgentRun pins chatTaskId
-> ContextCompiler (short context + memory + knowledge) -> Inference
-> optional Tool call through Governance/Ledger
-> approval required: chat-approval/v1 Checkpoint + WAITING_FOR_USER
-> exact Approval ID + PostgreSQL Worker Lease -> same Run/ToolCall resume
-> bounded synthesis inference
-> Assistant Message + Checkpoint/RunEvent -> memory candidate -> complete
-> complete/fail the same CHAT Root Task; later Messages retain Task history
```

An accepted LangGraph Chat `PLAN_PROPOSED` crosses Runtime into the Project public API. Java creates
and persists the PROPOSED TaskPlan, Child Tasks and DAG, and only the owner may approve/activate or
cancel it. TypeScript owns no plan ID or lifecycle. M54-PR3A can default-off invoke planning, then
stores `chat-plan-review/v1` and waits on the same Run before any planned inference or Tool effect.
M54-PR3B resumes the ACTIVE plan under the worker lease, records each Step result and carries plan
progress through approval/UNKNOWN checkpoints before final synthesis.

Provider reasoning is ephemeral UI evidence. It is not Message or reusable Memory content.
For a real streamed call, Inference records the first useful content/reasoning/Tool-call chunk once
under the active ModelCall claim. Java creates redacted Agent/Model/Tool spans and propagates W3C
context to TypeScript/Sandbox; telemetry loss never changes this business flow.
M53-PR1 keeps approval recovery in Runtime/PostgreSQL. Pending/rejected/mismatched approval never
executes the effect; ledger Replay precedes another approval check. UNKNOWN remains blocked for a
Tool-specific M53-PR2 adapter and is never redispatched. M53-PR2 supports read-only Workspace
postcondition verification for Document/Coding write/delete, stores only hashed evidence, and
continues Chat through terminal Replay. MCP mutations/commands remain UNKNOWN without a verifier.

## Project/Coding

```text
Project -> ProjectDirectory -> Conversation -> Task -> active TaskPlan/PlanStep
-> SourceRepository -> isolated directory-bound Workspace/worktree -> pinned Coding Run
-> Governance -> ToolExecutionLedger -> Project/OCI Sandbox effect
-> Git/Patch/Test Artifact -> Reviewer evidence -> local SourceMerge CAS -> acceptance
```

GitHub repository discovery/import/private checkout is official Marketplace MCP only. Local
projects use opaque Local Bridge handles. Browser requests never submit arbitrary server paths.
New Coding Runs pin both ProjectDirectory and Workspace. ProjectDirectory is a logical relative
source identity; only its source root binds an executable Sandbox Workspace until a separately
verified subdirectory/Git isolation design exists.

Recovery capture composes Blueprint/TaskPlan/Conversation/Artifact/Model/Tool/Approval evidence
through owner APIs. Live Git HEAD/status/tracked and untracked Patch is read only inside the exact
OCI Workspace. Runtime persists an immutable content-hashed snapshot; UNKNOWN becomes a blocker and
no contributing lifecycle authority moves into the snapshot.

Autonomous execution claims one Runtime-owned ProjectCodingJob for an ACTIVE PlanStep, provisions
one Task-isolated Workspace, runs the pinned model/Tool loop, pauses for exact Governance approval,
and requires successful Test/Patch/Commit plus a distinct Reviewer before completion. Every active
Workspace byte/Git/document/commit-preparation effect crosses the disposable OCI Sandbox. Approval
prepares only a local manual-first SourceMerge; remote refs remain untouched.

A paused or blocked Coding Job may be handed to a different active Agent and another same-directory
Conversation. Runtime persists the exact source/target Job+Run, both immutable configuration snapshots, Workspace and
immutable Recovery Snapshot, marks the source Job HANDED_OFF and terminally cancels its Run with an
exact handoff marker without terminating the PlanStep, and
starts a new fenced Job against the same Workspace. The target context includes bounded Project
Memory plus recovery evidence. After normal review/SourceMerge completion, a separate fenced
finalizer writes idempotent Project Memory and asks Project to archive the Workspace. UNKNOWN
evidence blocks target execution until reconciliation; changing models never retries it blindly.

## Multi-Agent

```text
Java Runtime Run-configuration snapshot -> TypeScript LangGraph.js Supervisor
-> plan/delegate/handoff/review proposal
-> Java validation/persistence -> isolated child Workspace + pinned child Run
-> Artifact/Review evidence -> Java completion/handoff closure
```

TypeScript owns no DB, Provider key, Git, Tool effect or authoritative checkpointer. Provider-backed
Supervisor decisions are boundary-stepped through Java ModelPool/ModelCallLedger.

## Tool and Sandbox

```text
model ToolCall -> pinned Agent capability/schema/network/workspace validation
-> Governance when required -> atomic ToolExecutionLedger claim
-> Java owner Application API or authenticated OCI Sandbox compute
-> terminal replay | UNKNOWN reconciliation -> Checkpoint/Artifact
```

OCI child containers are non-root, read-only-rootfs, capability-free, network-none and mount only
the exact Workspace subpath. There is no Python host-process fallback.

## MCP Marketplace

```text
Publisher -> stable Marketplace Entry -> immutable ServerVersion -> remote Transport
Installation -> pinned ServerVersion -> Connection/AuthRef -> PENDING_VALIDATION
-> MCP initialize + bounded Tool discovery -> revision-fenced CapabilitySnapshot -> ACTIVE
-> capability/runtime call
```

An OAuth-protected generic Connection first follows RFC 9728 resource discovery, an exact
pre-registered authorization-server/client match, RFC 8414/OIDC metadata and Authorization Code +
PKCE S256. The one-use state is tenant/user/Connection/revision bound and redacted after consume.
The encrypted grant ends in `PENDING_VALIDATION`, never directly in `ACTIVE`.

Tooling owns every row and encrypted credential. Installation pins never move implicitly when the
catalog current Version changes. Registry metadata is external input that must be validated and
snapshotted; Project and Agent may retain only opaque/versioned references through public APIs.
Remote qualification never holds a database transaction. Its success snapshot, health observation
and Connection transition commit atomically only if the Connection revision still matches. A first
failure becomes `ERROR`; a failure after activation becomes `DEGRADED`; reconfiguration invalidates
the current snapshot. Only `ACTIVE` Connections may list or call Tools. Existing pre-V1040 ACTIVE
Connections remain legacy-active until an explicit reconfiguration or qualification.

## Memory tiers

- Short: current Conversation/ContextPackage and ContextSnapshot.
- Medium: Project blueprint, requirements, module boundaries, environment, task state and
  acceptance evidence.
- Long: reviewed reusable User/Project preferences and knowledge consolidated across sessions.

Agents propose candidates; Java-owned lifecycle/review decides durable reuse.
