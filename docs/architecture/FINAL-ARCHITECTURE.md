# SpaceAgent V2 Final Architecture

Date: 2026-09-15 (current implemented boundary corrections; historical flows below are milestone evidence)

## 1. System overview

The active Java backend is the modular monolith in `apps/platform-server`. It is the
single HTTP/SSE entry point and the authoritative owner of core Identity, Agent,
Project, Task, Inference configuration, Knowledge, Conversation, Memory, Runtime,
Checkpoint, Governance Policy/Approval, Automation Schedule/Execution, and
ToolExecutionLedger state in PostgreSQL.

```text
client
  -> platform-server
       -> PostgreSQL (spaceagent_platform)
       -> model/embedding providers
       -> optional TypeScript multi-agent-orchestrator
       -> optional sandbox-worker

private Admin Web
  -> platform-admin-server (spaceagent_admin)
       -> mTLS + exact-scope service JWT -> platform-server owner APIs
```

The old monolith and five Java services are removed from the active tree. Their final
source is available only in a private archive not included in this public snapshot.

## 2. Module map

| Module | Current responsibility |
| --- | --- |
| identity | registration, credential hashing, JWT sessions, refresh rotation/revocation, Organization/Tenant creation/list/switch, unique owner, durable invitations, membership transfer/leave, cleanup Job/Step and DELETED tombstone lifecycle |
| agent | tenant/user-scoped single editable current configuration, immediate creator/OWNER edits and exact temporary cross-owner approval, immutable per-Run snapshots, ModelPool/direct-provider compatibility, hashed Agent API keys and Knowledge/Skill bindings; no AgentVersion lifecycle |
| inference | encrypted providers, durable health probes, deterministic priority/weighted/cost/latency ModelPool snapshots, known-safe per-attempt fallback, immutable prices, Organization reservation/budget settlement, trustworthy cost evidence and allowlisted HTTP execution |
| knowledge | Document/Chunk lifecycle, parsing/chunking boundary, embedding metadata, retrieval |
| conversation | Conversation, validated PROJECT/CHAT active Task focus, Message, sequence integrity, context snapshots |
| context | request-scoped ContextPackage compilation and token budgeting |
| memory | selective candidate evaluation, explicit review/consolidation, scoped recall |
| runtime | tenant-aware AgentRun with immutable per-Run configuration snapshot, Project Task/PlanStep or Chat Root Task binding, revision CAS, Worker Lease/Fencing, durable Continuation, ProjectCodingJob and ProjectRunHandoff, RunEvent/SSE cursor, RunStep, Checkpoint, Recovery, Project Execution Context Snapshot/Coding Recovery Package, Handoff, Delegation, Review, snapshot-based Tool dispatch and SkillVersion context/evidence |
| tooling | immutable JSON-Schema Tool catalog, Organization-scoped immutable SkillVersion registry, bounded SearXNG/HTTP adapters, MCP Marketplace/installations, dynamic advertised-Tool calls, official GitHub Remote MCP metadata discovery and Spring Security OAuth2+PKCE, encrypted/refreshable Connection AuthRef with revision CAS, official-SDK Streamable HTTP, `get_me`/`search_repositories` mapping, fenced MCP Invocation Ledger, encrypted/expiring/consumed checkout grants, authenticated disposable OCI sandbox compute and durable ToolExecutionLedger |
| integration | public/internal HTTP security adapters and SSE contract |
| project | persisted mutually exclusive PROJECT/CHAT Task and TaskPlan/PlanStep intent; Project/ProjectDirectory/ProjectIntakeJob, SourceRepository with opaque MCP Connection/Invocation provenance, directory/source-bound private/public MCP managed Workspace/worktree through an ephemeral credential port, scoped file/list/Git/document operations, Local Bridge, ProjectBlueprint and Membership |
| artifact | immutable Coding Runtime Patch/Commit/Test/Acceptance evidence metadata |
| observability | tenant-scoped Monitoring plus owner-scoped Trace list/detail/stats; redacted AgentRun/Model/Tool/Automation/Collaboration/Artifact projections, Runtime-backed stop; Micrometer/Prometheus, opt-in OpenTelemetry OTLP, Agent SLO metrics, Grafana and Alertmanager; telemetry is not recovery truth |
| governance | Organization approval policy, durable request/decision/expiry, optional separation of duties, exact-operation one-use consumption, Coding Runtime mutation gates |
| automation | Organization Schedule CRUD/pause/archive/manual trigger, Spring cron/IANA timezone, PostgreSQL-clock due occurrence, Governance approval wait, Runtime Continuation dispatch, prepared Chat and at-most-once Execution/UNKNOWN |

Every implemented business module uses `api`, `application`, `domain`, and
`infrastructure` packages. Cross-module work goes through public Application APIs.

## 3. Dependency rules

- Domain code is framework/provider independent.
- Conversation, Memory, Knowledge, and Project do not depend on Runtime.
- Inference does not depend on other business modules.
- Runtime coordinates modules through public APIs; modules do not access another
  module's persistence implementation.
- HTTP controllers are adapters and never import repositories/persistence.
- shared-kernel contains technical primitives only.
- AgentDefinition contains configuration identity only and owns no project/task/runtime
  state.

The executable rules live in `scripts/check-architecture.sh`,
`PlatformModuleArchitectureTest`, and `SharedKernelArchitectureTest`.

## 4. Request execution flow

The active Chat path is:

```text
HTTP/SSE
 -> ChatRuntimeApplicationApi
 -> Agent configuration snapshot
 -> reserve user/assistant Message pair
 -> source-Message-idempotent CHAT Root Task + Conversation focus
 -> AgentRun immutable chatTaskId + request checkpoint
 -> Memory recall + Knowledge retrieval
 -> ContextCompiler
 -> InferenceExecutionApi
 -> optional Runtime Tool dispatcher
 -> ToolExecutionLedger / Governance / owner Application API
 -> approval required: versioned Checkpoint + WAITING_FOR_USER
 -> exact owner resume + PostgreSQL Worker Lease + same ToolCall
 -> assistant Message + ConversationContextSnapshot
 -> Memory candidate evaluation
 -> terminal checkpoint + AgentRun completion
```

`PlatformChatHttpController` never calls inference, tooling, or Conversation
persistence directly.

M54-PR1 gives every ordinary Chat request a durable goal. The shared Task aggregate has mutually
exclusive PROJECT and CHAT scopes; Chat creates no hidden Project and no automatic TaskPlan. Each
source USER Message owns one Root Task, later Messages remain Task history, Conversation stores only
the active focus, and AgentRun separately pins `chatTaskId`. Approval/UNKNOWN waits keep the Task
IN_PROGRESS; Run success/failure completes/fails it.

M54-PR2 generalizes the same Project-owned TaskPlan/PlanStep aggregate to an explicit CHAT scope.
An accepted LangGraph.js `PLAN_PROPOSED` is checked against the pinned Chat Root Task and Run, then
Java generates all Child Task/Plan/Step IDs, validates the bounded DAG and persists a PROPOSED
version. Source-Run/hash replay is idempotent; owner-only review can approve, activate or cancel.
This foundation does not yet invoke planning from ordinary Chat or execute PlanSteps.

M54-PR3A adds default-off automatic invocation before normal Chat inference. A LangGraph
`COMPLETED` decision continues the compatible path; `PLAN_PROPOSED` persists through Java and stops
the same Run at `WAITING_FOR_USER` with `chat-plan-review/v1`. HTTP/SSE expose
`WAITING_PLAN_APPROVAL` and the exact TaskPlan reference. M54-PR3B resumes only the ACTIVE plan under
a worker lease, executes dependency-ready Steps with Model/Tool evidence, preserves plan progress
through approval/UNKNOWN waits and completes the original reply through final synthesis.

M53-PR1 makes the approval branch durable. The exact pending Tool call and bounded synthesis state
stay in an append-only Runtime checkpoint; the original assistant reservation remains pending.
Resume is tenant/owner scoped, requires the exact Approval ID and holds the existing worker lease.
A duplicate resume is BUSY or terminal, and Tool ledger Replay precedes another approval check.

M53-PR2 adds the adjacent UNKNOWN branch. Runtime stores `chat-tool-unknown/v1`, exposes
`WAITING_RECONCILIATION`, and runs only an allowlisted read-only Workspace postcondition verifier.
Exact content/absence evidence is hashed before Tooling atomically reconciles the persisted
InputHash/revision to SUCCEEDED. Chat then sees terminal Replay and continues under the same worker
lease. Clients cannot select a terminal state or supply evidence; MCP mutations and commands with no
generic verifier remain UNKNOWN.

M56-PR1 adds the durable Project plan dispatcher above the existing per-Step Coding loop. An owner
submits one reviewed execution binding for an APPROVED/ACTIVE plan. Java selects one ready Step by
sequence, persists an idempotent ProjectCodingJob, synchronizes Step/Child/Root lifecycle, and
creates the successor Job in the same transaction as reviewed completion. This is deliberately
single-flight; parallel Workspace/Agent/SourceMerge ordering requires a later ADR. UNKNOWN and
lease ambiguity never advance the plan.

## 5. Runtime lifecycle

Java Runtime owns durable `AgentRun`, `RunEvent`, execution cursor, `RunStep`,
`Checkpoint`, `Recovery`, and `Handoff` records. Canonical Project runs pin UUID/FK
references to one Project child Task, active TaskPlan, and matching PlanStep; Project
validates and advances the Task/PlanStep through its public Application API. Normal
transitions move a run through created/in-progress, optional
waiting-for-tool/waiting-for-user states, and a terminal completed/failed/cancelled
state. Every accepted boundary appends a monotonic RunEvent and updates the durable
cursor. Checkpoints are written at meaningful execution boundaries rather than kept in
process memory. M23 routes every AgentRun mutation through expected-revision CAS. A
continuation worker additionally requires the active PostgreSQL Worker Lease token and
monotonic fencing token, so a reclaimed stale worker cannot commit.

## 6. Context Engine

`ContextCompilerService` receives explicit contributions from system configuration,
Conversation history, recalled Memory, and Knowledge matches. It selects content within
the token budget and returns a request-scoped `ContextPackage`. Context is a compiled
runtime value and has no independent repository.

## 7. Memory lifecycle

Chat completion calls message evaluation. Ordinary messages produce no Memory record.
Only explicit durable preference/fact/decision/architecture signals may create a
`MemoryCandidate`. Candidate review and task completion consolidation are separate
operations; accepted high-confidence candidates may become task/project/user scoped
memories.

## 8. Handoff and recovery

Recovery reconstructs state from the latest Java-owned Checkpoint, unfinished RunStep,
Conversation snapshot reference, and ToolExecutionLedger entries. Acceptance compares
durable resume fields. Handoff snapshots carry typed execution context between runs;
Python process memory is never a recovery source. Accepted recovery enqueues a deduplicated
`RESUME_RUN` Continuation; any platform replica may claim it with `SKIP LOCKED`, acquire
the Run lease, and perform the fenced resume.

M51-PR2 adds an owner-scoped immutable Coding Recovery Package for strict directory-bound Runs.
Runtime stores a content hash, idempotency hash, exact Run scope and canonical payload; Project,
Conversation, Artifact, Inference, Tooling and Governance retain their original authority. Live
Git HEAD/status/tracked and untracked Patch evidence is read only inside the exact OCI-mounted
Workspace. The package exposes hashes and safe references instead of Tool arguments/results,
Model responses, raw Checkpoint payload or Approval operation hashes. UNKNOWN remains a blocker,
not a retry instruction.

M51-PR3 adds the pre-Task Project intake path for a managed remote source-root directory. Project
owns the durable leased/fenced Job, source/Conversation/Run-configuration pins, bounded redacted
inspection, proposal hash and confirmation results. A task-independent detached worktree is mounted
read-only into the OCI Sandbox with no network; ToolExecutionLedger and ModelCallLedger retain the
read/model evidence. Before exact-owner hash confirmation no Blueprint, Task or TaskPlan exists.
Confirmation creates and activates those entities in one Java/PostgreSQL transaction. Local Bridge
intake remains unavailable because the server cannot bypass the OCI-visible Workspace boundary.

M51-PR4 adds Runtime-owned, leased/fenced autonomous execution for one active PlanStep. It creates
or reuses one Project-owned writable Workspace per child Task, persists bounded model/action/review
progress, and uses stable Model/Tool identities for crash replay. All active Workspace File,
Document, Git, write/delete, test and commit-preparation paths now cross the authenticated OCI
Sandbox through an immutable helper; the host Workspace gateways are removed. Governance pauses
before exact mutations, distinct Reviewer evidence precedes Run/PlanStep completion, and only a
manual-first local SourceMerge proposal is prepared. Remote refs remain untouched.

M51-PR5 adds a user-directed `ProjectRunHandoff` above the generic child-Agent Handoff. It binds a
paused/blocked source Coding Job and Run, immutable Recovery Snapshot, exact Workspace and a target
same-directory Conversation plus active Agent/current ModelPool. The source Run becomes
terminally cancelled with an exact handoff marker without cancelling the IN_PROGRESS PlanStep; the
source Coding Job itself is `HANDED_OFF`, and the target Coding Job reuses the Workspace
and receives bounded recovery and Project Memory context. Completed target jobs enter a separate
leased/fenced finalizer that writes idempotent Project Memory and delegates Workspace archival to
Project. UNKNOWN effects remain blockers and remote refs are never updated.

## 9. ToolExecutionLedger idempotency

PostgreSQL enforces `UNIQUE(agent_run_id, tool_call_id)`. A terminal entry is replayed
without repeating the side effect. An ambiguous worker failure becomes `UNKNOWN` and
requires reconciliation; it is not blindly retried.

Governance is checked before a new Coding Runtime Tool claim for file writes/deletes and
commands. The approval capability binds tenant/requester/action/resource plus the exact
Run/Workspace/toolCall/argument digest and is atomically consumed once. A terminal,
input-hash-matching Tool ledger replay performs no side effect and therefore does not
consume another approval.

### 9.1 Runtime Tool suite

The M33 registry is the single executable capability truth for Agent validation, model Tool
schemas and Runtime dispatch. It exposes `echo`, Web Search/HTTP Fetch, Knowledge Search,
Workspace File Read/List and Git Status/Diff, dynamic MCP, GitHub MCP Search/Repository,
Document Read/Write and Coding write/delete/command operations. Unknown, unavailable or
unpinned IDs fail before an effect.

Runtime derives tenant/user/Project/Task scope from the AgentRun and Workspace ID from bounded
arguments. Network tools additionally require the pinned Agent network flag, public HTTPS and
Governance authorization. SearXNG is disabled unless configured. MCP calls resolve an authorized
Connection and advertised schema/annotations before call. Workspace and document operations are
limited to one READY writable managed worktree; Project owns bytes/Git, Knowledge owns retrieval,
Tooling owns remote calls, and Runtime persists no duplicate Tool state.

M36 hardens this boundary: generic public HTTP connects only to the exact resolver-approved
public address list, while MCP additionally requires `PLATFORM_TOOLING_MCP_ALLOWED_HOSTS`.
Workspace Git output, Patch size and untracked-file fanout are bounded before evidence reaches
Runtime/Artifact. Knowledge request bytes, parsed characters, document references and Chunk
fanout are bounded at both HTTP and Application boundaries.

M37 adds browser-only Cookie auth adapters without changing Identity session ownership or the CLI
contract. Refresh Cookies are HttpOnly, SameSite=Strict and session-scoped; Access Tokens stay
process-memory-only in React. Conversation, Message, Organization, Agent, ModelPool, Knowledge and
Memory reads use bounded/batch repository operations. Optional orchestration HTTP requires an
internal Bearer and returns no raw exception detail.

## 10. Persistence ownership

`spaceagent_platform` is the active database. The active Flyway chain contains 63
migrations through V1051 and is owned only by platform-server:

- `db/platform-server`: Identity, Agent, Project, Task, Inference, Knowledge, Memory,
  Conversation, ProjectDirectory/Workspace binding, Governance Policy/Approval, Automation Schedule/Execution, versioned MCP
  Publisher/ServerVersion/Transport/Installation, MCP OAuth state/encrypted grant,
  Connection CapabilitySnapshot/health, Official Registry Source/SyncJob/immutable Snapshot/
  review Candidate,
  Observability/Trace read views, and migration evidence.
- `db/platform-runtime`: Runtime, Worker Lease/Continuation including Automation dispatch,
  ToolExecutionLedger, Skill definitions/versions, Conversation snapshots and immutable Project
  execution context snapshots.

PostgreSQL is the durable source of truth. Redis is not used as durable platform state.
Migration watermarks, ID maps, counts, checksums, and FK evidence remain in the platform
database and M8 evidence documents.

## 11. Java/external compute boundary

`services/multi-agent-orchestrator` is the only external AI orchestration boundary.
`workers/sandbox-worker` executes one bounded OCI request and returns a result. Neither
external process imports a business DB client or persists Project/Task/Conversation/Memory/
AgentRun/Checkpoint/Tool ledger state. Java wraps sandbox calls with the durable ledger.

M34 makes Docker SDK 7.2.0 the worker's default executor. Coding Runtime keeps one
Governance/Ledger/Checkpoint/Artifact flow and calls a compute-only Tooling API after claim;
Project host command execution is disabled. Each request uses a non-root, networkless,
read-only, capability-free, resource-bounded container with only the exact Workspace volume
subpath mounted. Real runc acceptance passed; gVisor `runsc` remains a required deployment
acceptance before public-untrusted release enablement.

ADR-007 assigns Multi-Agent reasoning to TypeScript `services/multi-agent-orchestrator`
using LangGraph.js. Java remains authoritative for Organization, ModelPool, Agent,
Task/Plan, Runtime, Checkpoint, Ledger, Memory, Workspace metadata, secrets, and all
side effects. M38-PR2 retired the Python AI-orchestrator after TypeScript parity; the
Python Sandbox worker remains a separate OCI infrastructure boundary.

M17 implements the TypeScript service skeleton with pinned LangGraph.js/Zod, strict
`multi-agent/v1` JSON contracts, deterministic Supervisor routing, and a bounded HTTP
adapter. M18 adds a strict Java HTTP adapter selected only by
`platform.multi-agent-orchestrator.mode=http`; Java correlates the response, advances its
own cursor, and records the accepted command as a RunEvent. M22 adds specialist, Handoff,
and Reviewer proposals plus Java-owned durable Delegation/Review state, isolated child
Workspaces, pinned child Coding Runs, Artifact-scope validation, and Handoff closure after
approval. The TypeScript service remains compute-only, has no
database/checkpointer/secret/side-effect dependency, and default routing remains `none`.

M28-PR1 adds optional Provider-backed Supervisor reasoning without changing that ownership.
TypeScript returns one strict `MODEL_REQUESTED` proposal; Java validates the Run/ModelPool/
logical-call scope, executes a dedicated RunStep through M27 routing, budget and
ModelCallLedger, and reinvokes LangGraph with only standardized result and selection evidence.
Malformed or ineligible routes, invented Agent IDs, cyclic plans and recursive model requests
fail closed. A completed result replays after a boundary crash without repeating the Provider
call; UNKNOWN remains blocked. Deterministic routing stays the default.

M23 adds PostgreSQL-authoritative runtime coordination. RunEvent append serializes on the
Run row and allocates one sequence in the same transaction. JSON replay and SSE use an
exclusive durable sequence cursor; SSE publishes the sequence as its event ID and honors
`Last-Event-ID`, so a client can reconnect to another replica without replay gaps.

M24-PR1 adds Organization-scoped operational monitoring through read-only
`platform_observability_*` views. Source tables remain authoritative; monitoring stop
delegates to Runtime. Cost is deliberately null/incomplete until a versioned pricing
policy exists.

M24-PR2 adds Organization Governance Policy and durable ApprovalRequest/Decision. Coding
Runtime consumes the public Governance API before file mutation or command execution;
the database performs exact-scope, expiring, one-use consumption. OWNER/ADMIN manage the
policy and decisions through the Organization Governance UI. TypeScript, Redis, Trace and
frontend state own no approval decision.

M24-PR3 adds authoritative Automation. Spring parses cron while PostgreSQL clock and
`SKIP LOCKED` own due selection; unique fire keys deduplicate occurrences. Each occurrence
captures the Agent current configuration, passes Governance, creates Conversation/AgentRun, and enters the M23
`AUTOMATION_EXECUTION` Continuation handler. Prepared Chat runs on the fenced Run. Known
outcomes are durable; ambiguous worker loss becomes UNKNOWN instead of a blind retry.

M24-PR4 adds backend Trace list/detail/stats through disposable `platform_trace_*` views.
AgentRun is the root; Model/Tool ledgers and durable Automation/Collaboration/Artifact
evidence produce redacted spans. Every query filters tenant + authenticated owner. No
writable Trace state, Redis recovery dependency, prompt/tool/model payload, fake cost or
invented first-token timestamp is introduced. Frontend source is intentionally unchanged.

M52-PR1 makes the operational stack executable: Micrometer exposes Prometheus JVM/HTTP/Hikari and
bounded SQL-projection Agent metrics, Micrometer Tracing bridges automatic Spring observations to
OpenTelemetry, and an explicit OTLP/HTTP switch exports sampled traces to Tempo. Grafana provisions
JVM and Agent operations dashboards; Prometheus evaluates infrastructure and Agent SLO rules;
Alertmanager groups and silences alerts. No metric label contains tenant/user/resource IDs or
payloads, and every telemetry backend remains disposable.

M52-PR2 pins the reviewed OpenTelemetry GenAI Development subset to upstream commit `94f432d7`.
Java emits bounded `invoke_agent`, `chat` and `execute_tool` spans and uses official W3C propagation
for the TypeScript LangGraph and Python Sandbox HTTP boundaries; both workers use official
OpenTelemetry SDK extraction and default-off OTLP export. Content and business IDs are never span
attributes. V1048 records the first useful Provider stream chunk exactly once under the active
ModelCall claim, using a PostgreSQL timestamp and monotonic dispatch duration. Trace and Prometheus
project this TTFC evidence; telemetry still cannot drive any business transition.

## 12. Deployment topology

Default Compose services are PostgreSQL, database-init and platform-server.
The private Docker-API-owning sandbox-worker is available only through the `sandbox` profile
and publishes no host port.
The default Maven reactor contains shared-kernel and platform-server. Python services
are started only when their HTTP modes are selected. The TypeScript Multi-Agent service is also
standalone and absent from default Compose/routing. The Go CLI remains under `cli/`.
The clean-room React UI lives at `apps/web`. M32-PR2A removes the copied compatibility UI
and `packages/web-contracts`, then establishes the approved multilingual public entry,
authentication presentation and animated Runtime concept as the first-party visual base.
The optional Compose `web` profile exposes its Nginx build on port 8080. Monitoring,
Governance, Scheduled Tasks and other authenticated product surfaces remain backend-only
until reintroduced against authoritative contracts; no browser state owns business truth.

Native GitHub source-control compatibility was removed in M38-PR2. The Marketplace
path uses the official Remote MCP endpoint plus `PLATFORM_GITHUB_MCP_CLIENT_ID`,
`PLATFORM_GITHUB_MCP_CLIENT_SECRET` and an exact callback allowlist. Tooling discovers OAuth
endpoints, encrypts tokens with the MCP key and rejects direct token/PAT configuration for
the official profile. The Go CLI stores Bridge token/local-path mappings only in a local
mode-0600 profile file; platform HTTP contracts carry opaque root handles only.

Browser deployments are same-origin by default. A reverse proxy should serve the client
and platform API under one origin; the platform does not ship a permissive wildcard CORS
configuration.

Multiple platform-server replicas may safely poll the same Runtime Continuation queue and
serve RunEvent replay. This guarantee covers Java Runtime claim/mutation/event coordination;
external Provider/Tool effects retain their separate ledger and `UNKNOWN` reconciliation
semantics and are not claimed exactly-once.

## 13. Legacy archive policy

The former `backend/` and gateway/identity/agent/chat/knowledge service sources are
`REMOVED_FROM_ACTIVE_TREE / AVAILABLE_IN_GIT_TAG`. They have no Maven module, Compose
service, route, or active Flyway ownership. Legacy Runtime Rollback is no longer
supported. See `docs/archive/LEGACY-DECOMMISSION.md`.

## Intentional limitations

- A paid model or embedding response is not required for architecture acceptance;
  missing credentials must fail explicitly at the real HTTP adapter.
- M34 disposable Docker/runc isolation and Coding command cutover are implemented and live
  accepted. Ordinary runc is not claimed as a sufficient hostile multi-tenant boundary;
  public-untrusted configuration remains rejected until Linux/gVisor `runsc` acceptance.
- Optional Python HTTP transport exists, but default Chat execution remains Java-owned.
- Project SourceRepository, public/private GitHub metadata import, encrypted OAuth
  connections, opaque Local Workspace Bridge registration, managed Workspace/worktree
  provisioning, private MCP checkout grant consumption and isolation-keyed child Workspaces
  are implemented. Automatic Chat Root
  Tasks, Local Bridge coding execution, and assignment policy remain later capabilities.
- Artifact Patch/Commit/Test/Acceptance metadata is implemented for the Coding Runtime.
  M29 adds reviewed manual-first delivery: exact approved Review/Artifact evidence,
  Project-owned SourceMergeJob, Git commit preparation, Governance-gated local-ref CAS and
  explicit CAS rollback. Remote branches are never updated.
  Observability monitoring uses disposable tenant-scoped read views and non-authoritative
  Prometheus/OpenTelemetry projections; Governance gates
  Coding mutations; Automation Schedule/manual/due triggers execute through Governance and
  Runtime Continuation.
- MCP/command-specific UNKNOWN reconciliation, remote automatic merge, webhook/repository-event
  Automation triggers, and effect-aware automatic Automation retries are not implemented.
  M29 consumes the existing merge policy only for explicit local reviewed integration.
- Monitoring/Trace cost is populated only for calls settled against immutable prices;
  unpriced/incomplete cost remains unavailable. V1048 records first useful streamed chunk timing
  once at the Provider boundary and projects its P95 into tracing and Agent SLO metrics. Redacted,
  commit-pinned GenAI Agent/Model/Tool spans and W3C propagation now cover Java, TypeScript
  orchestration and Sandbox HTTP. An outbound production Alertmanager receiver and live OTLP
  collector/Tempo ingestion still require deployment acceptance. The optional Web
  profile does not imply parity for every historical page.
- The M22 Multi-Agent authority loop and M28 Provider-backed decision boundary are
  implemented: TypeScript/LangGraph.js owns ephemeral policy/reasoning while Java owns
  ModelPool calls, Delegation, isolated Workspace/child Run, Handoff, Artifact review and
  approval. Automatic merge, long-running graph continuation and default production cutover
  remain unimplemented; Python is limited to the OCI Sandbox Worker.
- Organization invitation tokens are durable, digest-only, expiring, revocable and
  single-use; authenticated acceptance is bound to the invited external identity. Token
  delivery/UI is not implemented. Empty Organizations become immediately inaccessible in
  DELETING state. M25-PR2C implements the physical cleanup worker, the
  ordered owner participants, Runtime lease drain, managed Git deletion, PostgreSQL scheduler
  and final DELETED Tombstone. Client Local Bridge roots and future external object stores
  remain their respective owners' deletion responsibility.
- Agent current configuration can bind an active visible ModelPool or retain a compatible direct
  provider/model pair. Chat pins deterministic candidate evidence; every known-safe fallback
  attempt is ledgered. Scheduled probes, priority/weighted/cost/latency routing and optional
  Organization quota/budget settlement are implemented. Adaptive learning remains later.
- Agent create/save is immediately effective; release review/publish/rollback is intentionally absent;
  existing create/update auto-publish remains a compatibility path. Separate reviewer
  roles, review comments, and scheduled activation are not yet implemented.
- PROJECT/CHAT scoped TaskPlan/PlanStep DAG, approval/activation, Root Task current-plan pointer,
  Conversation active Task focus, and Project Task-scoped Runtime transitions are implemented.
  Chat LangGraph proposals now persist generated Child Tasks and normalized plan structure;
  M54-PR3A automatic invocation stops at review; M54-PR3B completes lease-fenced Chat PlanStep
  execution and progress-aware Tool continuation. M56-PR1 adds owner-triggered, serial durable
  Project Plan auto-dispatch through reviewed Coding Job completion and terminal Plan/Root closure.

## Trusted Beta release boundary

M30-PR1 is the backend release stop line. `docker-compose.release.yml` requires explicit
Secrets; startup validation rejects unsafe audience/adapters/temp Workspace/schema settings;
V1051 release readiness, liveness, graceful shutdown, preflight, verified PostgreSQL backup/
restore and the restart API golden path are implemented. The release is API/CLI-first,
private/invite-only and trusted-code-only. A real staging Qwen Provider is verified. M31
implements GitHub's official remote MCP host OAuth 2.1, official repository mapping and
private checkout boundary. M50-PR2B adds generic pre-registered MCP OAuth with PKCE/resource
binding and qualification handoff. Real browser/account verification still requires operator-owned
OAuth client credentials and remains a deployment acceptance action.
M50-PR3 adds fixed-origin Official Registry synchronization as an asynchronous, bounded and
secret-sanitized metadata pipeline. Synchronized candidates are not installable until a private,
recent-MFA SystemAdministrator review publishes a supported remote version into the local catalog.

The complete corrected target is documented in
`TARGET-PRODUCT-BUSINESS-ARCHITECTURE.md` and ADR-007.
# M64 Knowledge URL and non-Project document boundary

Knowledge owns canonical URL refresh and USER/ORGANIZATION Document Workspace metadata, quota and operation
evidence. Tooling owns ledgered OCI byte transport under exact opaque mounts; Runtime and HTTP only coordinate
through public APIs. No hidden Project/Git state or host path exists. UNKNOWN mutations require hash-only
postcondition proof, and cleanup deletes bytes before metadata while preserving User-owned data across
Organization deletion.

## M64 Artifact object authority

Artifact owns immutable content identity, staging publication, exact Project/Knowledge/Artifact references,
retention, legal holds and fenced deletion. Local and S3-compatible adapters own bounded bytes only; the latter
uses the pinned official MinIO Java SDK and issues only exact GET capabilities of at most 300 seconds. Cleanup is
bytes-first and any hold, active claim or storage ambiguity remains BLOCKED. Bucket coordinates, filesystem paths,
signed URLs and credentials are never durable business state.
