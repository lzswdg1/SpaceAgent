# ADR-065: Bounded Project Ready-Wave Selection

- Status: Accepted
- Date: 2026-09-08
- Milestone: M58-PR2-U01
- Owners: Runtime / Project

## Decision

Runtime owns a pure, deterministic ready-wave decision for Project PlanSteps. The input is a
Project-provided step snapshot; no Runtime domain type imports Project persistence or framework
types. A wave contains only `PENDING`/`READY` steps whose every dependency is `COMPLETED`, ordered
by `(sequence, id)` and bounded by an explicit positive maximum.

`FAILED`, `CANCELLED`, and `BLOCKED` dependencies classify waiting downstream steps as blocked. A
blocked/UNKNOWN path is never redispatched by this selector. `IN_PROGRESS` steps are not selected.
Unknown dependency IDs and invalid limits are rejected rather than guessed.

## Consequences

M58-PR2-U01 adds no persistence, worker claim, Workspace, Git, Tool or HTTP effect. V1055 will
make the selected wave/concurrency claim authoritative across replicas; later units provision one
isolated Workspace per selected step and introduce reviewed merge barriers.
