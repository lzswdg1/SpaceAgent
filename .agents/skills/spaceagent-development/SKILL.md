---
name: spaceagent-development
description: Implement SpaceAgent fixes, features, cleanup, configuration, and documentation with a risk-proportional workflow. Use the architecture Skill only for authority or lifecycle redesign and real-e2e only for explicitly authorized external acceptance.
---

# SpaceAgent Development

Start from the current source and preserve unrelated work. Choose the lightest workflow that still
proves the changed behavior. Process size follows risk, not repository size, historical milestone
count, or raw file count.

## Choose a workflow first

Read `AGENTS.md`, current Git status, and the affected code and tests. For ordinary work, read only
the header and relevant active or recent section of `.agent/CURRENT.md` or
`.agent/BACKEND-PLAN.md`. Do not load complete historical logs unless the task depends on them.

Classify the change using [workflow-modes.md](references/workflow-modes.md):

- `FAST` is the default for local fixes, dead-code deletion, documentation, configuration, tests,
  and stable single-module behavior.
- `STANDARD` covers a bounded public API or persistence change owned by one business module.
- `STRICT` covers authority or lifecycle changes, destructive schema work, authentication,
  secrets, durable side effects, cross-runtime contracts, or release and deployment acceptance.

Do not promote a change merely because the repository is large. Do not downgrade a destructive
or cross-owner change merely because the net diff deletes code.

## Execution rules

### FAST

- Make one coherent change, normally in one commit.
- Do not create a milestone, Work Unit batch, ADR, planning-only commit, or status-document fanout.
- Inspect direct references, run the closest test or static check, and run `git diff --check`.
- Do not run full Maven, all Compose profiles, migrations, or rebuild local services unless the
  changed surface requires them or the user explicitly requests them.
- For documentation or Skill-only changes, structural validation and diff checks are sufficient.

### STANDARD

- Keep one bounded implementation scope, normally one owner module plus its adapter and tests.
- Use one commit unless a real rollback or interruption boundary exists.
- Run affected domain, application, and HTTP tests. Run PostgreSQL validation only when SQL,
  persistence mapping, transaction behavior, or migration behavior changed.
- Update one authoritative document only when the public contract or durable operating behavior
  changed. Do not copy the same status into historical plan documents.

### STRICT

- Use the architecture Skill. Create a tracked plan only when the work genuinely spans independent
  migration, recovery, ownership, or rollout phases.
- Split work at real rollback or authority boundaries; never manufacture five Work Units to satisfy
  a cadence.
- Run the focused union once after implementation stabilizes. Run a full regression and release
  gates once at closure when the change is release-sensitive.
- Update `CURRENT.md`, `BACKEND-PLAN.md`, `VALIDATION-BATCH.md`, or an ADR only when each file adds
  durable recovery or architecture value.
- Treat `.agent/V2-REFACTOR-PLAN.md` as history, not a routine development journal.

## Maintenance and local activation

Code verification and local deployment are separate decisions. Read
[runtime-feedback-loop.md](references/runtime-feedback-loop.md) only when a task needs a running
service, container, restart, or local smoke test.

- Default to no runtime activation after source changes. Passing affected tests is sufficient unless
  the requested behavior must be demonstrated through a running entry point.
- Reuse healthy PostgreSQL and supporting containers. Do not run `docker compose down`, delete
  volumes, or restart unrelated services during ordinary maintenance.
- Prefer a host-run application against existing dependency containers for repeated Java edits.
- When activation is required, choose exactly one: host-run the changed application, recreate its
  existing container without building for environment-only changes, or rebuild only its image for
  image/source changes.
- Never run full-stack `docker compose ... --build` as an automatic completion step. Reserve it for
  a changed multi-service contract or an explicitly requested release rehearsal.

## Permanent boundaries

- Java and PostgreSQL own authoritative business state. TypeScript and Python are compute workers.
- Modules communicate through public APIs, not another module's repository, DAO, mapper, or table.
- Preserve tenant isolation, audit and effect ledgers, `UNKNOWN` semantics, Run snapshots,
  Governance decisions, and Workspace or Git compare-and-set rules.
- An Agent has one current editable configuration. Do not reintroduce removed AgentVersion
  lifecycle complexity.
- Applied Flyway migrations are immutable.
- Do not restore retired runtimes, Redis, Kafka, or worker-owned business authority.

## Verification and repair

Use the verification Skill and select only the rows affected by the change.

- A production-code, migration, shared-test-setup, or runtime-wiring repair invalidates broader
  evidence that exercised the previous code.
- A test-fixture-only or assertion-only repair may rerun the failed set when production code and the
  already-executed production path are unchanged.
- Documentation and status corrections do not invalidate product-test evidence.
- Do not rebuild merely to produce ceremonial exact test totals or repeat an already valid gate.

## Completion

Report the behavior changed, important files, evidence run, and intentionally unrun gates. FAST and
STANDARD work must not manufacture backend milestones or claim release readiness.
