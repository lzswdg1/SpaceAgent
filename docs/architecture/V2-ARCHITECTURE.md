# SpaceAgent V2 Architecture

## Repository

- `apps/platform-server`: active Java 21 + Spring Boot modular monolith
- `apps/platform-admin-server`: optional independent Java administrator identity/security control
  plane; separate `spaceagent_admin`, no tenant business access (M40-PR2)
- `apps/admin-web`: independent private React/TypeScript administrator client over `/admin/v1/**`;
  no tenant or business authority (M41-PR1)
- `services/multi-agent-orchestrator`: M22/M28 TypeScript + LangGraph.js Supervisor/specialist/reviewer policy with optional Provider-backed decisions; compute-only and not the durable state owner
- `workers/sandbox-worker`: optional authenticated Docker/OCI isolated execution worker
- `contracts`: canonical Protobuf schemas and shared JSON fixtures
- `cli`: current Go CLI source location
- `apps/web`: clean-room React/TypeScript product UI foundation rebuilt in M32-PR2A from the approved SpaceAgent entry prototype; it currently owns the multilingual public entry and authentication presentation only, and its Nginx build is available through the optional Compose profile `web`
- Retired Java backend and five service sources: removed from the active tree; available
  available only in a private archive not included in this public snapshot

There is no active `apps/cli` directory in this worktree. The Go CLI remains at `cli`.
M32-PR1 moved and audited the historical compatibility UI. At explicit user direction,
M32-PR2A then removed that UI and its Web contract package from the active tree and created
a minimal first-party foundation. No removed operations page is claimed as active parity.

## Implemented Java Core Modules

- identity: 用户、认证、Organization/Tenant、唯一 OWNER、成员/邀请生命周期、Active Organization 切换、CleanupJob/Step 控制面、账户资料
- agent: AgentDefinition、单一可变 CurrentConfiguration、立即生效的 ModelPool/Provider 绑定、模板
- project: persisted Project/ProjectDirectory、mutually exclusive PROJECT/CHAT scoped Task and TaskPlan/PlanStep、SourceRepository、GitHub/Local Bridge、directory/source-bound isolation-keyed Workspace/worktree、scoped File/List/Git/Document operations、versioned ProjectBlueprint、DAG/approval/execution、serial durable Project Plan auto-dispatch、reviewed SourceMergeJob/local-ref CAS rollback、Membership/authorization
- conversation: Conversation、validated PROJECT/CHAT active Task focus、Message、ContextSnapshot
- memory: User/Project/Task Memory 及其生命周期
- knowledge: 文档、Chunk、RAG、Retrieval、URL refresh 与 USER/ORGANIZATION non-Project Document Workspace
- context: ContextCompiler、Selection、Compression、Token Budget
- runtime: tenant-aware AgentRun、immutable per-Run configuration snapshot、Project TaskPlan/PlanStep or Chat Root Task binding、revision CAS、Worker Lease/Fencing、durable Continuation、RunEvent/SSE cursor、Step、Checkpoint、Recovery、Project Execution Context Snapshot/Coding Recovery Package、Handoff、Agent Delegation/Review、snapshot-based Tool dispatcher and SkillVersion context/evidence
- inference: Provider/Model、加密 Secret、连接测试、ModelPool 优先级/Fallback 解析、Model Gateway、Embedding、Provider Adapter
- tooling: immutable JSON-Schema Tool Registry、Organization Skill/immutable SkillVersion、bounded SearXNG/HTTP、MCP Marketplace/
  Installation/Connection、dynamic advertised Tool calls、official SDK Streamable HTTP、
  GitHub MCP OAuth/search/discovery、Official Registry bounded Snapshot/review、disposable OCI
  Sandbox Gateway、Execution Ledger
- observability: tenant-scoped Overview/Usage/Realtime/Session、owner-scoped Trace list/detail/stats、redacted Run/Model/Tool/Automation/Collaboration/Artifact correlation、Runtime-backed stop、Micrometer/Prometheus、可选 OpenTelemetry OTLP、低基数 Agent SLO/Grafana/Alertmanager
- governance: Organization Policy、durable ApprovalRequest/Decision、expiry/separation-of-duties、exact-operation one-use consumption、Coding Runtime mutation gates
- automation: Organization Schedule、Spring Cron/IANA timezone、PostgreSQL-clock occurrence、Governance wait、Continuation/fenced prepared Chat、at-most-once Execution/UNKNOWN
- integration: active REST/SSE HTTP adapters
- shared: 极小通用内核

M74 supersedes the former Agent release lifecycle. Agent owns one mutable current configuration and Runtime owns one
immutable secret-free configuration snapshot per Run. V1081 preserves exact historical Run evidence and removes the
former review/release APIs, Java authority and storage. See ADR-079.

M75 adds Organization collaboration without restoring releases. The Agent creator or current Organization OWNER saves
the one current configuration directly. Another active writer creates a temporary Agent-owned proposal linked to an
exact Governance approval; only the current Identity-owned OWNER can approve it. Pending proposals never enter Runtime,
and terminal proposal bodies are erased so they do not become version history. See ADR-080.

M17 implements the compute-only TypeScript `multi-agent-orchestrator` contract and real
LangGraph.js graph. M18 adds an opt-in Java HTTP adapter. M22 expands the graph with
bounded specialist/handoff/reviewer routes while Java persists Delegation/Review,
provisions isolated child Workspaces, starts pinned child Coding Runs, and closes approved
Handoffs. TypeScript owns no durable state or side effects and remains disabled by default.
M23 makes Java Runtime coordination multi-replica safe: PostgreSQL-clock Worker Leases,
monotonic fencing tokens, AgentRun revision CAS, `SKIP LOCKED` Continuations, atomic
RunEvent sequencing, and `Last-Event-ID` replay. Redis and Trace remain non-authoritative.

`artifact` owns Coding Runtime Patch/Commit/Test/Acceptance metadata. M24-PR1 implements
Observability over disposable `platform_observability_*` SQL views; source domain tables
remain authoritative and cost stays unknown without pricing. M24-PR2 implements Java/
PostgreSQL Governance and exact-operation, expiring, one-use approval consumption before
Coding Runtime file/command side effects. M24-PR3 implements authoritative Automation:
PostgreSQL-clock/`SKIP LOCKED` schedule materialization, Governance-gated occurrences and
prepared Chat through a dedicated M23 Continuation handler. Redis/Bull/frontend timers own
no schedule or execution truth.

M24-PR4 adds backend-only authoritative-evidence Tracing through disposable
`platform_trace_*` views. AgentRun remains the root authority and source Ledgers/Events own
every span fact. Trace queries are tenant+owner scoped, payload-redacted, cost-incomplete,
and cannot drive recovery or execution. No frontend source is changed in this increment.

M52-PR1 adds non-authoritative operational telemetry without replacing those views. Spring Boot
Micrometer produces a real Prometheus scrape and bridges automatic HTTP observations through
OpenTelemetry; OTLP/HTTP export is operator-enabled and targets Tempo or another compatible
collector. A bounded refresher reads only `platform_trace_*` views and exposes global Agent
Run/Model/Tool/UNKNOWN/latency/token/cost-completeness/collaboration gauges without tenant or
resource labels. Grafana visualizes them and Prometheus routes SLO rules to Alertmanager. Every
replica exposes the same database projection, so queries use `max`; telemetry never drives Runtime,
recovery, reconciliation, approval or billing.

M25-PR2B adds the Identity-owned empty-Organization cleanup control plane: the final
Membership leave atomically enqueues the exact ADR-025 Job/Step set; PostgreSQL owns
retention, `SKIP LOCKED` claim, lease/fencing, heartbeat, ordered evidence, retry and
BLOCKED state. Integration has a non-scheduled coordinator skeleton. No module purge,
external deletion or DELETED transition exists until PR2C.

M25-PR2C completes the current cleanup lifecycle with explicit owner APIs, an Integration
execution coordinator, PostgreSQL-only scheduler, Runtime lease drain, managed Git
worktree/mirror removal, ordered tenant-resource purge and Identity-owned DELETED tombstone.
User identity/Knowledge/USER Memory and foreign tenants remain intact.

M26-PR1/PR2 add the backend MCP Marketplace and an internal GitHub discovery contract. Java Tooling owns
tenant-scoped installation, encrypted AuthRef/OAuth state, public-HTTPS transport policy and
official-SDK calls. GitHub MCP supplies remote account/repository capability but cannot own
platform persistence. M26-PR3 adds a fenced control-plane MCP Invocation Ledger and imports
validated results into Project-owned SourceRepository with opaque Connection/Invocation
evidence. Native GitHub remains a compatibility adapter until M26-PR4 proves private managed
Workspace checkout without Project-owned credentials. M26-PR4 completes that path with an
encrypted 3-10 minute Tooling grant, Project credential port, Integration adapter,
environment-only Git authorization, consume/expiry redaction and stale-PROVISIONING
revision-CAS recovery. Real acceptance later proved the custom OAuth/checkout tools are not
directly compatible with GitHub's official remote MCP host-OAuth contract. M31 now discovers
protected-resource/authorization-server metadata, uses Spring Security OAuth2+PKCE, maps
`get_me`/`search_repositories`, refreshes encrypted tokens with revision CAS and derives the
existing Java-owned short checkout lease. M38-PR2 removes the superseded native GitHub
OAuth/API/checkout path; official remote MCP is now exclusive.

M50-PR1 splits stable Marketplace Entry identity from Tooling-owned Publisher, immutable
ServerVersion and ordered Streamable HTTP Transport metadata. V1039 backfills both built-in entries
and pins every existing/new Installation to an APPROVED Version through same-Entry composite foreign
keys. Catalog keeps legacy current fields as a compatibility projection. M50-PR2A advances this to
V1040: new/reconfigured Connections remain `PENDING_VALIDATION` until official-SDK initialize and
bounded Tool discovery produce a revision-fenced immutable CapabilitySnapshot plus safe health
observation; only `ACTIVE` can execute, while failed initial/revalidation probes become `ERROR`/
`DEGRADED`. Registry sync, generic OAuth, scheduled health and Agent MCP binding remain later work.
See ADR-042 and ADR-043.

M50-PR2B adds pre-registered generic OAuth without merging it into GitHub's account/repository
profile. RFC 9728 resource discovery, RFC 8414/OIDC authorization-server metadata, Spring Security
Authorization Code + PKCE S256, RFC 8707 resource binding, revision-fenced one-use state, encrypted
grant and compare-and-set refresh remain Tooling-owned. OAuth completion ends at
`PENDING_VALIDATION`; M50-PR2A is still the only activation gate. V1041 backfills OAuth-state
revision evidence and redacts consumed PKCE/provider sessions. DCR and Client ID Metadata Documents
remain later work. See ADR-044.

M27-PR1 adds PostgreSQL-authoritative Provider health scheduling. Remote `/models` reads run
outside transactions; short REQUIRES_NEW claim/complete transactions persist due/backoff,
lease/fencing and safe observation history. Redis/browser state is not scheduler truth.

M27-PR2 executes deterministic priority/weighted/cost/latency ModelPool snapshots. Chat pins
the snapshot hash and Inference uses one fenced ModelCallLedger entry per candidate attempt.
Only known-safe failure advances; UNKNOWN never falls back. Cost routing requires an active
immutable versioned price.

M27-PR3 adds optional Organization monthly hard limits and atomic worst-case reservation.
Known failures release, successful attempts settle actual tokens/cost and UNKNOWN retains its
reservation. ModelCall pins immutable price evidence; Observability/Trace show cost only when
complete and never estimate missing evidence.

M28-PR1 adds opt-in Provider-backed LangGraph Supervisor decisions through the existing
boundary-stepped protocol. TypeScript emits one bounded `MODEL_REQUESTED`; Java resolves the
authorized ModelPool and executes through M27 routing/budget/ModelCallLedger, then reinvokes
the ephemeral graph with a standardized result. Provider secrets, persistence and side
effects remain in Java. Deterministic M22 routing remains the default rollback path.

M29-PR1 closes the backend Coding delivery loop without remote write authority. Integration
accepts only an APPROVED Review covering the exact Patch/Commit Proposal; Project verifies
Workspace HEAD/Patch, prepares one Git commit and persists a SourceMergeJob. OWNER/ADMIN and
Governance gate local default-ref apply/rollback, both old-object CAS fenced. Results always
state `remoteUpdated=false`; remote push/PR/merge remains a trusted-client action.

M30-PR1 packages this backend as an honest Trusted Beta release candidate. Strict release
configuration, non-root/persistent Workspace ownership, V1041 readiness after M50-PR2B, graceful shutdown,
secret-safe preflight, verified backup/restore and the restart golden path are executable
gates. Public untrusted Coding is rejected until a hardened container/VM sandbox exists.

M33-PR1 replaces the `echo`-only runtime boundary with a 16-definition executable Tool
catalog shared by Agent validation, Inference schemas and Java Runtime dispatch. Web/HTTP,
Knowledge, READY managed Workspace file/list/Git/document, dynamic MCP, GitHub MCP and Coding
operations remain behind their owner Application APIs. Network and mutation policy flows through
Governance and ToolExecutionLedger; terminal calls replay, ambiguous mutations remain UNKNOWN,
and no new persistence table or non-Java authority is introduced. SearXNG is configuration-gated;
generic Chat approval/resume and Tool-specific UNKNOWN reconciliation remain later work.

M34-PR1 replaces host Coding command execution with an authenticated Docker SDK 7.2.0
sandbox worker. Java authorizes and claims once, Tooling performs compute without a duplicate
ledger, and Project owns the post-container Workspace/Git evidence. Child containers use an
immutable local image, exact Workspace volume-subpath, no network, non-root/read-only/
capability-free settings and bounded cgroup/log/time resources. Real Docker runc acceptance
passed; public-untrusted release stays fail-closed until Linux gVisor `runsc` acceptance.

M36-PR1 closes the repository-wide P1 review findings without changing authority. Public
Conversation/Run visibility is tenant+owner scoped; Conversation reserves User/Assistant
message pairs under a PostgreSQL row lock and never overwrites a sequence. Internal identity
headers are internal-route-only. Public HTTP pins the validated DNS answer, MCP adds an
operator host allowlist, and authentication/SSE/JSON/Knowledge/Git evidence paths have hard
admission and size bounds. Observability Compose ports are loopback-only by default.

M37-PR1 closes the P2 review findings. Browser refresh credentials use an Identity-owned
HttpOnly/SameSite Cookie transport while CLI refresh JSON remains compatible; React persists no
credential. Conversation/Message pagination and Organization/Agent/ModelPool/Knowledge/Memory
batch queries execute in their owning Java modules. Embedding and optional Python/TypeScript
orchestrator transports are bounded and authenticated. Web routes are lazy chunks and deployment
publishing/monitoring images are explicit and loopback-first.

M51-PR1 adds the Project -> ProjectDirectory -> Conversation hierarchy and immutable Workspace pins
for new Coding Runs. M51-PR2 composes a content-addressed Coding Recovery Package through public
owner-module APIs. Live Git HEAD/status/tracked and untracked Patch evidence is read only inside the
exact OCI-mounted Workspace; Runtime persists the point-in-time snapshot without replacing current
Blueprint, TaskPlan, Conversation, Workspace, Artifact, Ledger or Approval authority.

M54-PR1 adds source-Message-idempotent CHAT Root Tasks and immutable AgentRun pins without a hidden
Project. M54-PR2 extends the Project-owned TaskPlan/PlanStep model with mutually exclusive CHAT
scope: accepted LangGraph proposals carry content only, while Java creates Child Task/Plan/Step IDs,
validates the DAG and persists owner-review lifecycle plus source-Run/hash idempotency. Automatic
Chat Planner invocation stops at the M54-PR3A durable review checkpoint; M54-PR3B resumes the exact
ACTIVE plan under a worker lease, executes dependency-ready Steps and preserves Tool-wait progress.

## Module-Internal Boundaries

Every business module follows a four-layer package boundary:

```text
<module>/
├── api/
├── application/
├── domain/
└── infrastructure/
```

- `api`: public commands/queries, public DTOs, and public facades/ports exposed to
  other modules.
- `application`: use cases, application services, coordinators, and transaction
  orchestration.
- `domain`: aggregates, entities, value objects, domain services, domain events, and
  domain ports. Domain code remains framework-independent.
- `infrastructure`: persistence, external HTTP/gRPC, framework adapters, provider SDK
  adapters, and configuration.

The `shared` kernel is not a business module and intentionally uses only `api`,
`domain`, and `infrastructure`.

## State Ownership

- PostgreSQL: durable business and runtime/checkpoint/tool-ledger state
- Git + Worktree: source workspace state when project/workspace features use it
- Redis: cache/ephemeral state only; the active platform has no Redis source of truth
- S3/MinIO: external large-object reference boundary where configured

Temporal is not part of the current active implementation.

## Core Ownership

Project
└── Task
    ├── TaskState
    ├── current TaskPlan
    │   └── PlanStep DAG
    ├── Workspace
    ├── Conversation
    ├── TaskMemory
    └── AgentRun
        ├── RunStep
        ├── Checkpoint
        ├── ToolExecution
        ├── Handoff
        ├── Delegation -> isolated child Workspace/Run
        ├── Review -> Artifact evidence/decision
        ├── WorkerLease -> claim token/fencing/heartbeat
        └── Continuation -> retry/claim/fenced resume

AgentDefinition is only referenced by AgentRun.
Agent does not own Project, Task, Workspace or Conversation.

## Language Ownership

- Java owns state and domain
- TypeScript owns interaction and deterministic Multi-Agent Supervisor/specialist/handoff/reviewer policy
- Python owns only optional OCI Sandbox execution, never Multi-Agent orchestration
- Neither TypeScript nor Python owns authoritative business/Runtime state or Provider secrets

M56-PR1 keeps Project Plan execution in this ownership model: an owner provides one reviewed
execution binding, Java activates and serially materializes dependency-ready ProjectCodingJobs,
and reviewed completion atomically advances Step/Child/Plan/Root plus the successor Job. Parallel
ready-Step scheduling, automatic Agent assignment and remote Git updates are not implied.

M60-PR1 adds path-free Local Bridge materialization. Project/PostgreSQL own expiring sessions,
bounded chunks and immutable `MANAGED_SNAPSHOT` Source evidence. Finalize creates no Directory,
Task or Workspace. Normal provisioning mounts the Source read-only and exact Workspace writable
in OCI Sandbox, where the local Git baseline is created; no host-path fallback exists.

M61-PR1 pins exact Agent current-configuration MCP binding evidence and adds bounded official-SDK Resources and Prompts.
Experimental MCP Tasks are `DEFERRED_UPSTREAM / NOT IMPLEMENTED`: the client advertises no Tasks capability,
sends no Task method and returns `MCP_TASKS_UNSUPPORTED` for remote capability/results. Java Runtime
Continuation remains exclusively platform-owned. See `MCP-CAPABILITY-SUPPORT.md` and ADR-070.

M61-PR2 adds code-local, versioned capability-specific UNKNOWN verifiers for exact GitHub issue updates and local
Git config commands. They perform only bounded read-only MCP/OCI observations, persist hash-only evidence through
the existing Tool Ledger CAS and resume the same Runtime Run by terminal replay. Unsupported or inconclusive
effects remain UNKNOWN; HTTP clients cannot submit scope, verifier, verdict or evidence. See ADR-071.

M64-PR1 adds canonical public-HTTPS Knowledge URL refresh and Knowledge-owned non-Project Document Workspaces.
Document bytes are reachable only through exact `document-workspaces/{uuid}` OCI mounts; Runtime and HTTP call
the same owner API, Governance protects mutations and Tool Ledger owns effect replay/UNKNOWN. Reconciliation reads
only hash/size/existence proof and never redispatches an ambiguous write. Cleanup is bytes-first and fail-closed.

The target product/business model is defined in
`TARGET-PRODUCT-BUSINESS-ARCHITECTURE.md`; ADR-007 supersedes earlier target-language
statements that assigned final AI orchestration ownership to Python.

## Independent administration boundary

M40-PR2 implements the foundation of `apps/platform-admin-server`: a separate Java service and
`spaceagent_admin` database for non-tenant SystemAdministrator identity, password+TOTP MFA,
rotating refresh/CSRF sessions, dedicated access JWT, command journal and audit. It never connects
directly to `spaceagent_platform` and receives no Provider/MCP key. Platform-wide reads, dashboard
projections enter the M40-PR3 owner-module SystemAdministration Application APIs through dedicated
mTLS and <=60-second exact-scope service JWTs. V1036 persists User status/login/activity and V1037
adds activation plus platform command evidence; V1038 adds Identity-owned fenced User cleanup;
the Admin service exposes bounded Dashboard/User/Organization/redacted credential reads and stores
only their audit/command evidence plus an ephemeral stale Dashboard cache. M40-PR4 adds recent-MFA
User create/activate/suspend/restore and deletion preflight. M40-PR5 adds configuration-gated,
restart-safe owner cleanup, explicit Organization ownership blocking and a minimal User tombstone.
M42-PR1 adds Identity-owned Organization create/update/decommission commands plus owner-filtered
Inference Provider and Agent identity pages. Admin still owns no business state or secret.
M43-PR1 adds hard-refresh recovery through HttpOnly Refresh plus double-submit CSRF cookies while
keeping access/refresh credentials out of Web Storage. See ADR-034, ADR-035, ADR-036 and
`PLATFORM-ADMIN-CONTROL-PLANE.md`. M44-PR1 adds Identity-owned member pagination,
add/reactivate/role/remove and explicit atomic OWNER transfer; see ADR-037.
M45-PR1 adds Identity-owned global Cleanup projections and Admin-owned paginated Command evidence;
no Cleanup retry or UNKNOWN redispatch authority is added. See ADR-038.
M46-PR1 adds bounded owner-module User resource projections for 12 resource/effect kinds. The Admin
plane receives only redacted metadata through one exact scope and gains no content, secret,
impersonation, business persistence or effect-retry authority. See ADR-039.
M47-PR1 adds Admin-DB-only principal lifecycle, forced password replacement, TOTP/recovery-code
provisioning and durable Session revocation. Raw provisioning is first-response-only and never enters
command/audit persistence. See ADR-040.
M48-PR1 supersedes the multi-principal lifecycle: Admin V4 permits at most one non-tenant
SystemAdministrator, online cardinality-changing operations fail closed, self recovery-code rotation
requires recent MFA plus current password, and an offline startup-only break-glass reset is
request-fenced, secret-free in audit and revokes every Session. See ADR-041.
