# SpaceAgent V2

The production-grade V2 refactor, compatibility cleanup and project Skill suite are complete through M39. M40 adds an independent administration control plane. The active business Java backend is
`apps/platform-server`; `apps/platform-admin-server` owns only administrator security/control-plane state. Retired Java runtimes are absent from the active tree; their private archive is not included
in this public snapshot.

## Read First

- .agent/BACKEND-PLAN.md (authoritative backend-only work queue and exact next milestone)
- .agent/VALIDATION-BATCH.md (pending validation batch and checkpoint evidence)
- .agent/CURRENT.md
- docs/architecture/PRODUCT-ROADMAP.md
- docs/architecture/V2-ARCHITECTURE.md
- docs/architecture/FINAL-ARCHITECTURE.md
- docs/architecture/DEPENDENCY-RULES.md
- docs/architecture/TARGET-PRODUCT-BUSINESS-ARCHITECTURE.md
- docs/DEVELOPMENT.md
- .agent/PLANS.md
- .agent/V2-REFACTOR-PLAN.md when present

Use the project Skill suite:

- `.agents/skills/spaceagent-development` for routine implementation, fixes and milestone handoff;
- `.agents/skills/spaceagent-frontend` for React/TypeScript UI, public API/SSE integration,
  browser state, performance, accessibility and frontend security review;
- `.agents/skills/spaceagent-architecture` for business ownership, topology, persistence or
  cross-module architecture changes;
- `.agents/skills/spaceagent-verification` for deterministic regression and release gates;
- `.agents/skills/spaceagent-real-e2e` only for explicitly authorized live Provider/GitHub MCP
  acceptance. It never replaces deterministic validation.

## Final Topology

- Java business runtime: `apps/platform-server`
- Java administration runtime: `apps/platform-admin-server` (separate DB; no business persistence)
- React/TypeScript web client: `apps/web`
- Durable business/runtime state: PostgreSQL `spaceagent_platform`
- Target Multi-Agent orchestration: `services/multi-agent-orchestrator` (TypeScript + LangGraph.js)
- Optional isolated execution: `workers/sandbox-worker`
- Retired Java runtimes: REMOVED_FROM_ACTIVE_TREE / AVAILABLE_IN_GIT_TAG
- M51-PR5 cross-Conversation/model/Agent handoff, Project Memory consolidation and Workspace
  archival and M52-PR2 trustworthy streaming/cross-runtime telemetry are complete;
  M53-PR1 durable generic Chat Tool approval resume and M53-PR2 Workspace Tool-specific UNKNOWN
  reconciliation and M54-PR1 automatic Chat Root Task/Run binding are complete.
  M54-PR2 durable Chat TaskPlan proposal/Child Task DAG and owner review are also complete;
  M54-PR3A automatic Planner/review wait and M54-PR3B lease-fenced Chat PlanStep execution are
  complete behind a default-off flag. M55-PR1 adds the Organization Skill/immutable SkillVersion
  registry and exact current Agent configuration binding; M55-PR2 adds pinned Skill context and hash-only RunStep
  evidence for Chat/Project without granting capabilities.
  Java/PostgreSQL remain all business authority.

## Hard Rules

- No uncontrolled big-bang rewrite.
- Keep the repository buildable after every milestone.
- No new circular module dependencies.
- No cross-module Mapper/DAO access.
- shared cannot depend on business modules.
- AgentDefinition must not own runtime/project/task state.
- Conversation must not be the Agent orchestrator.
- Domain models must remain framework-independent.
- Python Sandbox workers must not own authoritative Project/Task/Conversation/Memory state.
- TypeScript Multi-Agent orchestration must not own business persistence, Provider secrets,
  Git side effects, Tool side effects, or authoritative Runtime checkpoints.
- Redis/Kafka are not active runtime dependencies and must not return as business authority.

## Milestone Workflow

The active product queue is backend-only. Do not modify `apps/web` or `apps/admin-web` unless the
user explicitly replaces this direction. TypeScript Multi-Agent and Python OCI Sandbox remain
backend compute components, not frontend work.

Classify every ad hoc request before creating a milestone:

- `FAST` is the default for local fixes, unused private-code deletion, documentation, Skills,
  tests, example configuration, and contract-preserving single-module changes. Use one coherent
  commit, the closest meaningful test or structural check, reference checks, and `git diff --check`.
  Do not create Work Units, validation batches, ADRs, or status-document fanout. Do not run full
  regression, all Compose profiles, migrations, or rebuild services unless the affected surface
  requires them or the user explicitly requests them.
- `STANDARD` covers a bounded supported API or additive persistence change owned by one business
  module. Keep one implementation scope and normally one commit. Run only affected domain,
  application, adapter, controller, PostgreSQL, or entry-point tests. Update at most the one
  authoritative contract document that actually changed.
- `STRICT` covers authority or lifecycle moves, destructive persistence, authentication,
  authorization, secrets, durable side effects and `UNKNOWN` semantics, cross-owner or cross-runtime
  contracts, sandbox boundaries, release, deployment, and external acceptance. Only STRICT work
  should normally create a tracked milestone, recovery Work Units, validation batches, or ADRs.

When a tracked plan explicitly declares its workflow mode, that declaration controls until the
milestone closes. STRICT Work Units are split only at real rollback, ownership, compatibility,
deployment, or recovery boundaries; there is no mandatory number of Units or files. Run their
focused union once after implementation stabilizes and run full regression and release gates once
at closure when the milestone is release-sensitive. A test-fixture-only or documentation-only
repair does not require another clean full run when production code and the already-exercised path
are unchanged.

When the user says start/next/continue, execute the active or single next planned milestone only if
one exists. If the tracked backend queue is complete and the user supplies a new objective, classify
that objective independently; do not manufacture a milestone for ordinary FAST or STANDARD work.
If no objective and no planned work exist, report that there is no queued work.

Continuous execution applies only to an authorized active tracked STRICT plan. Continue through its
real recovery Units until complete or genuinely blocked. Context rollover never authorizes live
external acceptance, pushing, destructive cleanup, frontend work, or any other action requiring
separate permission.

`CURRENT.md`, the active ExecPlan, and V2 status must be synchronized only when a tracked milestone
starts, completes, or changes scope. Routine FAST and STANDARD changes do not rewrite historical
status journals.

## Local Runtime Economy

Verification does not automatically authorize or require local deployment. Default to source-level
affected tests and leave an already running stack untouched. When runtime proof is needed:

1. reuse healthy PostgreSQL and other unchanged dependencies;
2. prefer a host-run changed application for repeated edit/test cycles;
3. recreate only the affected container without building when only environment values changed;
4. rebuild only the affected service image when packaged source, resources, dependencies, or its
   Dockerfile changed and container proof is required;
5. use full-stack/profile `--build`, Maven `clean`, Compose `down`, or cache removal only for an
   affected cross-service/release gate or explicit user request.

Never rebuild both a host-run process and its container merely to duplicate evidence. Always report
whether source was verified, a host runtime was activated, or a specific image was rebuilt.
