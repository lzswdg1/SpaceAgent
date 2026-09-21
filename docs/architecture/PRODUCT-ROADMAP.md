# SpaceAgent Product Roadmap

> Updated: 2026-09-15
> Status: M81-PR1 COMPLETE; optional guarded low-resource deployment, all features retained
> Short handoff: [`.agent/CURRENT.md`](../../.agent/CURRENT.md)
> Authoritative backend queue: [`.agent/BACKEND-PLAN.md`](../../.agent/BACKEND-PLAN.md)
> Full target blueprint: [`TARGET-PRODUCT-BUSINESS-ARCHITECTURE.md`](TARGET-PRODUCT-BUSINESS-ARCHITECTURE.md)

## Purpose

This file is the Git-tracked, human-readable implementation roadmap. It intentionally
stays shorter than the historical ExecPlan so a developer or a fresh coding agent can
open the repository anywhere and immediately determine what is complete, what is active,
and what must happen next.

## Sources of truth

Read these files in order:

1. `.agent/BACKEND-PLAN.md` — backend-only total target, completed stages, ordered queue and exact
   `Active-Work-Unit`/`Next-Work-Unit`; milestones are never executed as one large change.
2. `.agent/CURRENT.md` — active milestone, current branch/base, done/pending checklist,
   blockers, validation commands, and exact next action.
3. `docs/architecture/PRODUCT-ROADMAP.md` — completed delivery history and milestone boundaries.
4. `docs/architecture/TARGET-PRODUCT-BUSINESS-ARCHITECTURE.md` — final product/business
   architecture.
5. `docs/architecture/V2-IMPLEMENTED-FUNCTIONAL-ARCHITECTURE.md` — only capabilities
   proven by current code and tests.
6. `.agent/V2-REFACTOR-PLAN.md` — detailed historical decisions and validation evidence.
7. `docs/adr/` — architecture decisions that must not be silently reversed.

If these disagree, do not guess. Record the contradiction in the ExecPlan and resolve it
in an ADR before changing production architecture.

## Architecture that does not change

- Java `apps/platform-server` owns authoritative business state, Runtime, permissions,
  Provider secrets, ModelPool, Ledgers, Checkpoints, and side effects.
- PostgreSQL `spaceagent_platform` is the durable business/runtime source of truth.
- TypeScript + LangGraph.js is the final Multi-Agent reasoning/orchestration target, but
  it must not own business persistence, secrets, or side effects.
- Python AI orchestration and active Redis infrastructure are retired; Python remains only
  for the OCI Sandbox Worker.
- Cross-module work uses public Application APIs; controllers never access repositories.

## Delivery roadmap

| Milestone | Status | Deliverable | Explicit boundary |
| --- | --- | --- | --- |
| M13-PR1 | COMPLETE | Organization lifecycle foundation | Invitations and physical cleanup deferred |
| M14-PR1 | COMPLETE | Provider test and ModelPool priority/fallback foundation | No Agent binding or advanced routing |
| M15-PR1 | COMPLETE | Immutable AgentVersion `modelPoolId` binding and runtime pool resolution | Preserve direct Provider/Model; no version workflow or automatic retry |
| M15-PR2 | COMPLETE | Explicit AgentVersion Draft/Review/Publish/Deprecate/Rollback | Immutable snapshots and pinned Runs preserved; scheduling/reviewer roles deferred |
| M16 | COMPLETE | Conversation active Task, TaskPlan, PlanStep, child Tasks, approval | Project-scoped foundation; Conversation remains a container, not Runtime |
| M17 | COMPLETE | TypeScript `multi-agent-orchestrator` skeleton, LangGraph.js spike, shared Zod/JSON contracts | Deterministic compute-only spike; no production cutover |
| M18 | COMPLETE | Task-scoped Runtime, RunEvent, UUID/FK convergence, feature-flagged TypeScript adapter | Java remains execution authority; no production cutover |
| M19 | COMPLETE | SourceRepository, GitHub integration, Local Workspace Bridge | Browser cannot submit arbitrary server paths |
| M20 | COMPLETE | Workspace/worktree isolation, ProjectBlueprint, mid-term project memory | Parallel agents never share a writable worktree |
| M21 | COMPLETE | Single-Agent coding loop and Artifact/acceptance evidence | All writes remain ledgered |
| M22 | COMPLETE | TypeScript Supervisor/Subagents/Handoff/Reviewer | Deterministic LangGraph policy and Java durable authority; M28 later adds Provider reasoning |
| M23 | COMPLETE | Worker lease, CAS/fencing, continuation, SSE cursor, reliable Active-Active | Runtime coordination only; Provider/Tool effects retain ledger UNKNOWN semantics; Trace/Redis are not recovery truth |
| M24 | COMPLETE (BACKEND) | Automation, Governance, Observability, authoritative Tracing | Frontend validation/change explicitly deferred by user |
| M25-PR1 | COMPLETE | Durable Organization invitation lifecycle | Backend only; delivery/UI deferred |
| M25-PR2A | COMPLETE | Empty Organization cleanup ownership/retention/protocol audit | Architecture only; no deletion code claimed |
| M25-PR2B | COMPLETE | Durable cleanup Job/Step, claim/lease/fencing/retry control plane | No scheduler or module purge; final completion guarded |
| M25-PR2C | COMPLETE | Ordered owner cleanup participants and final DELETED transition | Managed external resources first; Identity finalizes last |
| M26-PR1 | COMPLETE | MCP Marketplace, installation and encrypted connection/auth references | Java owns policy/config; MCP owns remote capability |
| M26-PR2 | COMPLETE | GitHub MCP OAuth and public URL/repository discovery | Native GitHub becomes compatibility-only |
| M26-PR3 | COMPLETE | Project import through ledgered GitHub MCP tools | Project stores opaque Connection/Invocation evidence |
| M26-PR4 | COMPLETE | MCP-authenticated private repository Workspace materialization | Short-lived encrypted grant; no credential persistence in Project |
| M27-PR1 | COMPLETE | Durable scheduled Provider health probes | PostgreSQL claim/lease; no browser timer |
| M27-PR2 | COMPLETE | Real fallback and weighted/cost/latency routing | Every attempt remains ledgered |
| M27-PR3 | COMPLETE | Organization inference quota/budget/cost evidence | Versioned price evidence; fail closed |
| M28-PR1 | COMPLETE | Provider-backed TypeScript Supervisor reasoning | Boundary-stepped model request; Java owns ModelPool, budget, ledger and all secrets |
| M29-PR1 | COMPLETE | Reviewed manual-first source integration | Approved Review + Governance + Base-SHA CAS; no remote push or automatic merge |
| M30-PR1 | COMPLETE | Trusted Beta Production Release Candidate | Production preflight/readiness, backup-restore verification, golden path and runbooks; trusted code only |
| M31-PR1 | COMPLETE (BACKEND) | Official GitHub remote MCP host OAuth 2.1 and repository/checkout mapping | Real browser acceptance requires operator GitHub App/OAuth App credentials; no password/PAT fallback |
| M32-PR1 | COMPLETE / SUPERSEDED | Audit the pre-existing Web surface before replacement | No artifacts from the superseded UI remain active |
| M32-PR2A | COMPLETE | Clean-room React/Vite entry plus repository-wide removal of superseded Web artifacts and client-only aliases | Public entry/auth presentation only; `copy/browser` remains an isolated private reference and is excluded from public exports |
| M32-PR2B | COMPLETE | Wire native Identity/Organization authentication contracts and route test foundation | No Better Auth or removed compatibility aliases |
| M32-PR3 | COMPLETE | V2 Project/Task/Run product shell and recoverable Runtime event experience | Java remains authoritative; browser owns no durable Run state |
| M33-PR1 | COMPLETE | Executable backend Tool Registry and Runtime dispatcher for Web/HTTP/Knowledge/Workspace/Git/MCP/GitHub/Documents/Coding | Backend only; SearXNG is configuration-gated, Workspace tools are Project Task-scoped, every effect remains governed and ledgered |
| M33-PR2 | COMPLETE | Bind the Agent UI to Runtime capability risk/availability metadata | Browser owns no execution or approval truth |
| M34-PR1 | COMPLETE | Disposable OCI-container Sandbox Worker and Coding command cutover | Docker SDK 7.2.0 and real runc accepted; public-untrusted release stays blocked until real Linux/runsc acceptance |
| M35-PR1 | COMPLETE | Provider SSE streaming, ephemeral reasoning and Tool-result synthesis | Provider ambiguity remains UNKNOWN; reasoning is not Conversation/Memory state |
| M36-PR1 | COMPLETE | P1 tenant, authentication, SSRF, concurrency and resource-boundary remediation | No authority moved from Java/PostgreSQL; process-local admission is not durable business state |
| M37-PR1 | COMPLETE | P2 browser session/CSP, pagination/N+1, Embedding, orchestrator and deployment hardening | CLI auth remains compatible; browser Cookie is transport state, not authority |
| M38-PR1 | COMPLETE | Remove retired Gateway CLI, unused shared microservice infrastructure, Redis/Kafka startup and stale operator material | Active product contracts, Flyway history, orchestrators, GitHub and Sandbox compatibility remain unchanged |
| M38-PR2 | COMPLETE | Retire Python AI orchestration, native GitHub OAuth/API and host-process Sandbox execution | TypeScript Multi-Agent, official GitHub MCP, OCI Sandbox and Flyway history remain authoritative |
| M39-PR1 | COMPLETE | Repository-owned development, architecture, verification and real-E2E Skill suite | Skills encode current boundaries; source/ADRs remain authority |
| M39-PR2 | COMPLETE | SpaceAgent frontend development/review Skill | React remains presentation/transient state; Java/PostgreSQL remain business authority |
| M40-PR1 | COMPLETE (ARCHITECTURE) | Independent non-tenant Platform Administration backend architecture | Separate Admin identity/DB; platform owner modules retain business authority |
| M40-PR2 | COMPLETE | Independent Admin service, Admin DB, password+TOTP MFA, Session/JWT, command/audit and bootstrap foundation | No platform business read/write, internal client or frontend |
| M40-PR3 | COMPLETE | Read-only platform administration contract, dashboard, global User/Organization/activity and redacted credential inventory | Owner modules retain data authority; no direct Admin DB read of platform business state |
| M40-PR4 | COMPLETE | Activation-based User create, suspend/restore, session revocation and deletion preflight | All mutations remain owner-module commands with recent MFA, reason, idempotency and audit; physical deletion is disabled |
| M40-PR5 | COMPLETE | Fenced module-owned User cleanup, ownership resolution and release acceptance | Release overlay enables verified erasure; stable pseudonymized tombstone remains; no implicit ownership transfer |
| M41-PR1 | COMPLETE | Independent Admin Web over the complete M40 control plane | React owns no Admin/business authority; no secret retrieval or internal endpoint access |
| M42-PR1 | COMPLETE | Admin Organization create/update/durable delete and per-User Provider/Agent detail | Owner modules retain mutation/query authority; Admin has no business DB/secret access; frontend unchanged |
| M42-PR2 | COMPLETE | Admin Web Organization CRUD and per-User resource integration | React consumes bounded `/admin/v1` contracts only; real local stack accepted without external calls |
| M43-PR1 | COMPLETE | Admin hard-refresh Session recovery with double-submit CSRF | Refresh remains HttpOnly; access/refresh tokens never enter Web Storage; no other Admin gap in scope |
| M44-PR1 | COMPLETE | Admin Organization member pagination, role lifecycle and explicit OWNER transfer | Identity remains authority; Admin never impersonates or becomes a member |
| M45-PR1 | COMPLETE | Global Cleanup queue/blocker detail and paginated UNKNOWN/FAILED Command center | Evidence-only Cleanup; no blind retry or Admin SQL |
| M46-PR1 | COMPLETE | Per-User ModelPool/Project/Task/Workspace/Conversation/MCP/Knowledge/Memory/Automation/Run and risk-effect drill-down | Owner-module redacted reads only; no content, secret, impersonation or effect retry |
| M47-PR1 | COMPLETE | Administrator create/suspend/restore, forced password change, TOTP/recovery codes and Session revocation | Admin DB only; one-time material never persists in command/audit; auditor role deferred |
| M48-PR1 | COMPLETE | Converge Admin backend to exactly one non-tenant SystemAdministrator with self recovery-code rotation and offline break-glass | Admin V4 enforces singleton; M47 multi-principal lifecycle is superseded; frontend unchanged |
| M49-PR1 | COMPLETE | Align Admin Web to singleton SystemAdministrator security and recovery | No multi-principal controls; recovery codes stay one-response-only browser state; Java/Admin V4 remain authority |
| M50-PR1 | COMPLETE | Versioned MCP Publisher/ServerVersion/Transport model and Installation version pinning | No Registry sync, generic OAuth, Agent MCP binding, protocol expansion or frontend change |
| M50-PR2A | COMPLETE | Real MCP Connection qualification, Capability Snapshot and durable health evidence | Generic OAuth, scheduler, Agent binding and protocol expansion deferred |
| M50-PR2B | COMPLETE | Generic pre-registered remote MCP OAuth host onboarding and authorization lifecycle | DCR/Client ID Metadata Documents deferred; preserve GitHub specialized OAuth and Java/PostgreSQL authority |
| M50-PR3 | COMPLETE | Official MCP Registry snapshot synchronization and review lifecycle | Registry is untrusted external input; only explicit SystemAdministrator approval publishes a supported local remote version |
| M51-PR1 | COMPLETE | Project → ProjectDirectory → Conversation hierarchy and immutable Coding Run Workspace binding | New Project execution is directory-scoped; Java authority and exact Sandbox Workspace isolation remain mandatory |
| M51-PR2 | COMPLETE | Project Execution Context Snapshot and complete Coding Recovery Package | Compose existing owner state; do not duplicate authority |
| M51-PR3 | COMPLETE | Read-only OCI Project intake, durable proposal, Blueprint/Root Task/TaskPlan confirmation | Exact owner/hash confirmation required |
| M51-PR4 | COMPLETE | Durable per-PlanStep model/Tool loop in isolated writable OCI Workspace | Approval, Reviewer and reviewed local merge proposal included; no host effect fallback or automatic remote push |
| M51-PR5 | COMPLETE | Cross-Conversation/model/Agent Handoff, Project Memory consolidation and Workspace archival | Java persists handoff/memory authority; remote Git remains untouched |
| M52-PR1 | COMPLETE | Micrometer/Prometheus, OpenTelemetry OTLP, Agent SLO metrics, Grafana dashboard and Alertmanager foundation | Operational telemetry is disposable and redacted; PostgreSQL Runtime/Ledgers remain authority |
| M52-PR2 | COMPLETE | Trustworthy streamed first-token evidence and redacted Agent/Model/Tool OpenTelemetry spans across Java/TypeScript/Sandbox | GenAI conventions pinned at `94f432d7`; no prompt/response/Tool payload export and no telemetry authority |
| M53-PR1 | COMPLETE | Durable generic Chat Tool approval pause and same-Run resume | Runtime checkpoint + PostgreSQL worker lease; exact approval only, no frontend and no UNKNOWN retry |
| M53-PR2 | COMPLETE | Tool-specific UNKNOWN reconciliation adapters and Chat continuation | Read-only Workspace postcondition evidence only; never redispatch an ambiguous side effect |
| M54-PR1 | COMPLETE | Automatic per-Message Chat Root Task, Conversation focus and immutable AgentRun binding | No hidden Project; TaskPlan remains Project-only |
| M54-PR2 | COMPLETE | Durable Chat TaskPlan proposal, Child Task DAG and owner review lifecycle | Java persists LangGraph proposals; invocation/review wait is M54-PR3A and execution is M54-PR3B |
| M54-PR3A | COMPLETE | Automatic Chat Planner and durable same-Run review wait | Default-off until M54-PR3B; no model/tool execution before review |
| M54-PR3B | COMPLETE | Approved Chat TaskPlan resume and durable PlanStep execution | Worker lease + per-Step Model/Tool/approval/UNKNOWN evidence |
| M55-PR1 | COMPLETE | Organization-scoped versioned Skill Registry and exact AgentVersion binding | Inert bounded instructions only; Runtime injection/execution is M55-PR2 |
| M55-PR2 | COMPLETE | Pinned Skill context compilation and RunStep use evidence for Chat/Project | Skill never grants Tool/MCP permission or executes host code |
| M56-PR1 | COMPLETE | Serial durable Project TaskPlan auto-dispatch and terminal lifecycle synchronization | One reviewed execution binding; parallel assignment remains deferred |
| M57–M58-PR1 | COMPLETE | Backend-only Project control and durable per-Step assignment | Java/PostgreSQL authority; frontend remains excluded |
| M58-PR2 | COMPLETE | Bounded parallel Project DAG execution | Project-owned reconciliation preserves immutable TaskPlan/PlanStep evidence; Runtime only projects waiting/blocker state |
| M59-PR2 | COMPLETE | Provider-backed Supervisor staged policy cutover fixtures | Deterministic policy/telemetry evidence only; live Provider invocation remains `LIVE NOT RUN` |
| M60-PR1 | COMPLETE | Local Project immutable managed Source snapshots and OCI Workspace materialization | Source-only Finalize; no absolute paths or host-process fallback |
| M61-PR1 | COMPLETE | Exact Agent MCP binding plus bounded Tools/Resources/Prompts | Tasks are DEFERRED_UPSTREAM / NOT IMPLEMENTED and fail closed |
| M61-PR2 | COMPLETE | Capability-specific MCP/command UNKNOWN reconciliation | Exact local verifiers only; all unsupported/inconclusive effects remain UNKNOWN |
| M62-PR1 | COMPLETE | Event-driven Automation backend | Versioned Trigger, Webhook/Repository/Task/Follow-up admission, unique occurrence, governed Runtime dispatch and fail-closed retry/UNKNOWN |
| M63-PR1 | COMPLETE | Agent Version governance | Independent exact-hash review/approval, explicit auto-policy, lease-fenced schedules, tenant API and redacted Admin evidence |
| M64-PR1 | COMPLETE | Knowledge URL ingestion and non-Project documents | Secure refresh plus OCI/Governance/Ledger Document Workspace; V1068/80, full `647/647` |
| M64-PR2 | COMPLETE | Artifact object storage and retention | V1071/83, local + official MinIO SDK, B01/B02 and full `675/675` PASS |
| M65-PR1 | CANCELLED_TO_EXTERNAL_ACCEPTANCE | Production security and observability acceptance | Removed from deterministic queue without completion claim; exact Linux/runsc, private ingress and live telemetry/alert proof remains required and public-untrusted stays false |
| M65-PR2 | COMPLETE | Director review remediation and production wiring | B01 `67/67`, B02 `69/69`, B03 Java `88/88` + TS `15/15`; V1073/85 |
| M65-PR3 | COMPLETE | Deterministic backend release acceptance | Maven `687/687`, TS `15/15`, Python 3.12 `23/23`, package/Compose/architecture and disposable V1073 backup/restore PASS |
| M65-PR4 | COMPLETE | Director final remediation | B01 U01-U05 PASS; B02 U06-U07 `14/14`; final Maven `699/699`, package/architecture/boundary PASS |
| M65 | DETERMINISTIC_BACKEND_COMPLETE_WITH_EXTERNAL_GATES | Final production acceptance | Local deterministic remediation complete; independent Linux/runsc, production mTLS, live telemetry and credential-bound external gates remain mandatory |
| M66-PR1 | COMPLETE | AgentVersion Activation Schedule cancellation | Agent/PostgreSQL owns reason-hashed SCHEDULED-only revision CAS; OWNER/ADMIN HTTP + React confirmation/409 refresh; CLAIMED/terminal schedules stay immutable; V1078/90 and Maven `718/718` |
| M67-PR1 | COMPLETE | Remaining tenant/Admin frontend parity | B01/B02 PASS; Web `66/66`, Admin Web `22/22`, Maven `721/721`, full deterministic gates |
| M68-PR1 | COMPLETE | Default context-window alignment | New defaults `200000`; V1003/old rows retain `32768`; Web `67/67`, Maven `722/722` |
| M69-PR1 | COMPLETE | Slash-safe Provider Model test contract | External Model IDs move from route topology to bounded JSON input; Web `67/67`, Maven `723/723` |
| M70-PR1 | COMPLETE | Web brand favicon integration | Web `67/67`; built/served favicon evidence; no business/API authority changes |
| M71-PR1 | COMPLETE | GitHub MCP repository listing reliability | Primary-result preservation; secret-safe failure taxonomy; focused `6/6`, Maven `726/726`; live GitHub NOT RUN |
| M72-PR1 | COMPLETE | GitHub MCP collaborator repository fallback | Independent owner/private searches; private collaborator rows survive unsearchable login; focused `7/7`, Maven `727/727`; live GitHub NOT RUN |
| M73-PR1 | COMPLETE | Web Agent delete contract integration | Agent API `7/7`, Web `67/67`; backend soft archive remains authority |
| M74-PR1 | COMPLETE | Agent current configuration + Run snapshot foundation | V1079/91; create/save immediate; immutable Run snapshot; Maven `736/736` |
| M74-PR2 | COMPLETE | Agent consumer migration | Tool/Skill/MCP, Project, Runtime, Automation and Multi-Agent use Agent/Run snapshot identity |
| M74-PR3 | COMPLETE | Obsolete release contract/storage retirement | no live AgentVersion API/worker/Java/storage; Maven `662/662`, TS `15/15`, full gates pass |
| M75-PR1 | COMPLETE | Organization Agent collaborative edit approval | creator/current OWNER direct; ADMIN/MEMBER cross-owner waits for OWNER; V1082/94; Maven `673/673` |
| M76-PR1 | COMPLETE | Flat logical roots with retained physical storage | V1083/95; blank/repository roots, direct Conversations, admin-gated physical cleanup; Maven/Web/browser/backup/local activation PASS |
| M77-PR1 | COMPLETE | 19 functional review findings: Git lineage, scoped UI, paged history, durable Chat and Project startup | V1084/96; distinct coding/reviewer Agents retained; Maven/Web/Admin/browser/backup/local activation PASS; no live external acceptance |
| M80-PR1 | COMPLETE | Retain Milvus and add deployment-selected pgvector native vector/FTS backend | V1098/110; persisted exclusive API/Worker selection, strict scopes, no-paid restore/delete; Maven+Artifact infrastructure retry reconciled902/902, package/architecture/Compose/preflight PASS; no frontend/deployment/implicit migration/capacity claim |
| M81-PR1 | COMPLETE | Modular prebuilt-image + optional low-resource deployment, default-compatible SSE/child resource caps, secret-safe preflight | All components/features retained; Maven904/Worker29/guard5/real constrainedOCI/currentJar+PGboot/package/architecture/diff PASS; no frontend/paid/activation/4GiB-host capacity claim |

MCP/command-specific UNKNOWN reconciliation, public-untrusted deployment acceptance, remote automatic merge,
webhook/repository-event Automation remains
separately tracked work.

## Milestone completion contract

Every milestone must leave the repository buildable and update, in the same Git commit:

- `.agent/BACKEND-PLAN.md`;
- `.agent/CURRENT.md`;
- `.agent/V2-STATUS`;
- `.agent/V2-REFACTOR-PLAN.md`;
- this roadmap if ordering or scope changed;
- implemented/target architecture docs when capability boundaries changed;
- tests, migration evidence, ADR, and validation counts.

The final gate is focused tests, full Maven tests, package, architecture validation,
`git diff --check`, a clean worktree, and one milestone-scoped commit.

## Resume protocol

```text
git status --short
git branch --show-current
git log -3 --oneline
cat .agent/CURRENT.md
cat .agent/BACKEND-PLAN.md
cat docs/architecture/PRODUCT-ROADMAP.md
read the active milestone in .agent/V2-REFACTOR-PLAN.md
run the validation commands recorded in .agent/CURRENT.md
continue the first unchecked item; do not start a later milestone
```
