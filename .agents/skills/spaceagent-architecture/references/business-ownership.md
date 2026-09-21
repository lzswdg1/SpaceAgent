# Business and data ownership

| Module | Owns | Must not own |
| --- | --- | --- |
| identity | User, credential/session, Organization/Tenant, membership, invitation, active Organization, cleanup Job/Step/tombstone | Agent/Project/Run data |
| agent | AgentDefinition, one mutable current configuration, temporary Organization edit proposal with terminal payload erasure, Agent API key, direct Knowledge/Skill/MCP/Tool/model-pool references | membership/OWNER truth, approval decision, Provider secrets, Project/Task/Conversation/Run snapshots or state |
| inference | ModelProvider encrypted secret, ProviderModel, ModelPool/member/price, health probes, routing snapshot, budget reservation/settlement, ModelCallLedger including claimed first-stream-chunk evidence | Agent/project persistence or Tool effects |
| project | Project, ProjectDirectory, membership, mutually exclusive PROJECT/CHAT scoped Task intent and TaskPlan/PlanStep, SourceRepository MCP provenance, Local Bridge, directory/source-bound Workspace/worktree metadata, ProjectBlueprint, reviewed SourceMerge | Provider/MCP secret storage, Runtime checkpoints |
| conversation | Conversation, Message sequence, active Task reference, ContextSnapshot | model/tool orchestration |
| context | request-scoped ContextPackage selection/compression/token budget | durable Run control |
| memory | USER/PROJECT/TASK candidate, review, consolidation and recall | Conversation execution |
| knowledge | Document, Chunk, parsing/embedding metadata and retrieval | Agent or Runtime ownership |
| runtime | AgentRun including immutable per-Run Agent configuration snapshot and Project Task/Plan or Chat Root Task pin, RunStep, Checkpoint including versioned generic Chat approval/UNKNOWN resume state, Tool-specific reconciliation coordination, Recovery, RunEvent, immutable Project Execution Context Snapshot/Coding Recovery Package, leased/fenced ProjectCodingJob and ProjectRunHandoff, WorkerLease/fencing, Continuation, Handoff, Delegation and Review coordination | Mutable Agent configuration, Provider secrets or another module's tables |
| tooling | Tool registry/schema, Organization Skill/immutable SkillVersion registry, MCP Publisher/ServerVersion/Transport/installation/connection/AuthRef, revision-bound OAuth state/encrypted grant, CapabilitySnapshot/health observation, official Registry source/sync job/immutable snapshot/review candidate, GitHub MCP OAuth/tools/checkout grant, Sandbox gateway, ToolExecutionLedger | Agent/Project/Conversation persistence or business checkpoints |
| artifact | immutable Patch/Commit/Test/Acceptance evidence metadata | Git state or approval policy |
| governance | Organization policy and exact-operation Approval lifecycle | direct side effects |
| automation | Organization Schedule/Execution, due occurrence, approval wait and prepared Runtime continuation | browser timers or Redis truth |
| observability | disposable tenant/owner-scoped monitoring and trace read models plus redacted OpenTelemetry/Prometheus projections | recovery, billing estimation or source-domain writes |
| integration | HTTP/SSE/security and cross-module Application-API adapters | domain data and repositories |

## Durable stores

- PostgreSQL `spaceagent_platform`: business entities, ledgers, checkpoints, schedules and source
  metadata.
- Git/worktrees: source content, branches, commits and Workspace filesystem state.
- S3-compatible storage when configured: large-object content referenced by Java metadata.
- SQL observability/trace views: rebuildable projections, never authority.

Existing V1015 Native GitHub tables/columns remain only as immutable Flyway history and cleanup
targets. Active Java/API models do not expose Native GitHub authority.

## Platform administration boundary

M40-PR3 extends `platform-admin-server` from the M40-PR2 non-tenant SystemAdministrator identity,
password+TOTP/session, Admin command journal and append-only Admin audit in `spaceagent_admin`. It
owns no platform User/Organization/Provider/Agent/Project/Runtime data and has no
`spaceagent_platform` credential. Platform-wide projections and commands remain in their owning
platform-server modules. Their bounded read APIs and redacted wire contract are implemented in
M40-PR3. M40-PR4 commands and M40-PR5 durable cleanup keep mutation authority in owner modules;
Identity owns only User cleanup Job/Step and the tombstone.

## Identity and tenancy invariants

- Registration provisions a default Organization and OWNER membership.
- One Organization has one OWNER; role/membership changes are tenant-scoped and audited.
- Final-member departure makes the Organization inaccessible and drives ordered cleanup before
  Identity writes the DELETED tombstone.
- Every public resource read/write proves tenant and user/role ownership. Internal identity headers
  are accepted only on authenticated internal routes.
- Agent creator and current Organization OWNER may update an Organization Agent directly. Another
  active writer may only submit a temporary proposal; Governance records the exact approval and the
  current OWNER is the sole approver. Pending proposals never become Runtime configuration.
