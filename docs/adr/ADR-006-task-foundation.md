# ADR-006: Tenant-scoped Task Foundation

- Status: Accepted
- Date: 2026-08-22
- Scope: M11-PR2

## Context

M11-PR1 made Project identity, membership, and authorization authoritative in
PostgreSQL. Task remains only a framework-independent reference record even though
Runtime, Conversation, Context, and Memory already carry optional `projectId` and
`taskId` references. `ProjectOwnershipPort.findProjectIdByTask` therefore remains
fail-closed and TASK Memory has no canonical authorization ancestry.

Workspace, Git/source Repository, Coding Agent, Artifact, Task Assignment, and
Multi-Agent all depend on a durable Task identity. Implementing any of them before Task
would create another synthetic or caller-supplied source of truth.

## Decision

### Task is a Project-owned aggregate

Task belongs to exactly one Project and owns durable execution intent:

- title;
- goal;
- description;
- constraints;
- acceptance criteria;
- optional parent Task;
- lifecycle state.

Task does not own Runtime state, Workspace state, Agent assignment, source code, tool
execution, or artifacts. State changes are aggregate behaviors; no direct state setter is
exposed.

### Lifecycle

The allowed state transitions are:

```text
PENDING -> READY | CANCELLED
READY -> IN_PROGRESS | CANCELLED
IN_PROGRESS -> BLOCKED | COMPLETED | FAILED | CANCELLED
BLOCKED -> READY | CANCELLED
COMPLETED / FAILED / CANCELLED -> terminal
```

Intent may be edited only while the Task is non-terminal. A Project must be ACTIVE for
Task creation, intent updates, or state transitions. Tasks under an archived Project
remain readable but cannot be mutated.

### Authorization inherits Project Membership

Task has no separate membership table in this milestone. Tenant membership is the outer
security boundary and Project Membership supplies resource authorization:

| Project role | View Task | Create/edit intent | Work transitions | Cancel |
| --- | --- | --- | --- | --- |
| OWNER | yes | yes | yes | yes |
| ADMIN | yes | yes | yes | yes |
| MEMBER | yes | no | yes | no |
| VIEWER | yes | no | no | no |

Work transitions are `start`, `block`, `complete`, and `fail`. `markReady` is a planning
operation reserved for OWNER/ADMIN. This split lets Project members execute approved
work without changing its goal or acceptance criteria. Task Assignment is deliberately
not introduced.

### Persistence

`platform_tasks` is owned by the Project module. Task and Project references are native
PostgreSQL UUIDs. Constraints and acceptance criteria are JSONB arrays. Parent ancestry
uses a composite foreign key so a parent Task must belong to the same Project. Physical
deletion is not part of the public API; cancellation is a lifecycle transition.

The production Flyway locations already end at V1007, so the next legal migration is
`V1008__task_foundation.sql`. Like V1007, it remains inert only in existing
migration-isolation fixtures that intentionally omit the complete Project/Identity
schema; every runnable platform path creates the table and constraints.

### Application and HTTP boundaries

Task use cases are exposed through a separate `TaskApplicationApi` inside the Project
module. They do not expand `ProjectApplicationApi`. HTTP adapters use
`/api/v1/projects/{projectId}/tasks` and depend only on the public Task Application API.

Project lookup, active-Tenant validation, Project Membership roles, and active-Project
checks are centralized in a reusable Project application authorization policy instead of
being duplicated between Project and Task services.

### TASK Memory ancestry

Both in-memory and PostgreSQL Project ownership adapters resolve
`Task -> canonical Project` from Task persistence. Existing Memory authorization can
therefore authorize TASK scope through Project Membership without importing Task
persistence or changing Memory schema.

### Runtime and Conversation compatibility references

Existing Runtime and Conversation `project_id`/`task_id` columns remain `VARCHAR(36)` in
M11-PR2. They can carry UUID text but do not gain cross-module foreign keys in this
milestone. Converting them requires an audit of existing non-null references and belongs
to a separate Task-scoped Runtime objective. M11-PR2 does not claim that creating a Task
launches or binds an AgentRun.

## Consequences

- Task identity, intent, parent ancestry, and lifecycle become authoritative PostgreSQL
  state.
- TASK Memory obtains canonical Project authorization.
- Task remains independent of Runtime, Conversation, Workspace, Git, Agent assignment,
  Artifact, and Multi-Agent implementation details.
- Task-scoped Runtime binding and reference-type/FK convergence remain explicit future
  work rather than hidden coupling in this foundation.
