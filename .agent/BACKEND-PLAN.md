# SpaceAgent Public Backend Status

> Plan-Version: PUBLIC-SNAPSHOT-1
> Scope: BACKEND_ONLY / OPTIONAL_LOW_RESOURCE_DEPLOYMENT
> Updated: 2026-09-21
> Plan-State: COMPLETE
> Active-Milestone: NONE
> Next-Milestone: NONE
> Active-Work-Unit: NONE
> Next-Work-Unit: NONE
> Last-Completed-Work-Unit: M81-PR1-U01
> Last-Completed: M81-PR1 optional resource-budgeted deployment

The tracked backend queue is complete. This public snapshot intentionally omits private development
branches, commit identifiers, internal checkpoints and local deployment history. New product work must
be classified independently under `AGENTS.md`; do not manufacture a milestone when no queued objective
exists.

### M81-PR1 — Optional budgeted low-resource deployment

- Status: `COMPLETE`
- Goal: Retain the supported components while offering an optional resource-budgeted deployment profile.
- Current-Evidence: Source-level Java, TypeScript, Python, Go, Compose and architecture validation exists; current public evidence is summarized in `.agent/CURRENT.md`.
- Owner-Boundary: Java/PostgreSQL business authority is unchanged; deployment limits and workers remain infrastructure/compute concerns.
- Implementation: `.agent/LOW-RESOURCE-PLAN.md` and `docker-compose.low-resource.yml`.
- Acceptance: Default-compatible resource ceilings, a separately bounded Sandbox child, no deletion of normal components and safe negative configuration checks.
- Verification: Deterministic source, merged Compose and release-configuration gates; no paid Provider, OAuth or production action.
- Not-In-Scope: Production capacity certification, public-untrusted execution, live Provider/GitHub acceptance and repository publication.
- Final-Effect: Operators can select the normal or optional low-resource topology without changing business ownership.
- Done: The optional profile and its deterministic validation are present in this snapshot.
- Next: NONE.

| Work Unit | State | Boundary | Deliverable | Evidence |
| --- | --- | --- | --- | --- |
| M81-PR1-U01 | COMPLETE | Deployment configuration | Optional resource ceilings and validation | Public deterministic gates |

## Current architecture

- `apps/platform-server` is the Java business/API/Runtime authority over PostgreSQL
  `spaceagent_platform`.
- `apps/platform-admin-server` is the separate administrator security/control plane over
  `spaceagent_admin`; it owns no tenant business data or Provider/MCP secret.
- `services/multi-agent-orchestrator` and `workers/sandbox-worker` are replaceable compute only.
- `apps/web`, `apps/admin-web` and `cli` consume supported public/private HTTP contracts and own no
  durable business state.
- Milvus or pgvector is deployment-selected Knowledge index infrastructure; PostgreSQL retains
  authoritative metadata and lifecycle state.

## Current public limitations

- Public-untrusted code execution and production sandbox-escape resistance are not certified.
- Paid Provider calls, live GitHub OAuth/MCP, production databases and external acceptance remain
  separately authorized work.
- Release signing and hosted repository security settings remain operator-owned GitHub setup.

## Public repository identity

- Repository: `https://github.com/lzswdg1/SpaceAgent`
- CLI module: `github.com/lzswdg1/SpaceAgent/cli`

## Boundaries that must not return

- Retired Java runtimes, Redis/Kafka business authority, Python host-process execution and
  worker-owned business persistence.
- Project-owned GitHub OAuth/PAT storage, cross-module DAO/Mapper access or browser-owned durable
  lifecycle state.
- Blind retry of `UNKNOWN` Provider, Tool, Git or storage effects.
- The private `copy/browser` reference tree.
