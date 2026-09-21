# V2 ExecPlan Contract

`.agent/BACKEND-PLAN.md` is the authoritative ordered backend-only product queue. It must contain
exactly one `NEXT` milestone when none is active, or exactly one `IN_PROGRESS` milestone and no
`NEXT` while work is active. One `BLOCKED` milestone is an exclusive barrier and later work must
remain `QUEUED`. A user request to start/continue/advance automatically selects that milestone;
historical ExecPlan text must not override it.

Every queued milestone must contain exactly one `Status`, `Goal`, `Current-Evidence`,
`Owner-Boundary`, `Implementation`, `Acceptance`, `Verification`, `Not-In-Scope`, `Final-Effect`,
`Done` and `Next` field. The active Working Journal must identify an exact next action and recorded
validation state before an interrupted task is handed off.

Large milestones are not direct coding units. `.agent/BACKEND-PLAN.md` must decompose them into
ordered `Mxx-PRx-Uxx` rows with `Status`, `Bounded Scope`, `Deliverable` and `Focused Proof`.
Ordinary work implements one Unit per checkpoint commit and then continues automatically. Validation
is batched under BACKEND-PLAN section 7.2.1: five Units by default, predeclared ten for low-risk
related work, and always before milestone closure. IMPLEMENTED_PENDING_TEST is a Unit-only state,
not a completed milestone. The batch boundary Unit stays IN_PROGRESS while tests run; a validation
commit promotes the proven batch to COMPLETE. See `.agent/VALIDATION-BATCH.md` for outstanding
Units, code commits, required tests and results. Earlier per-Unit full-regression wording does not
override this cadence.

.agent/V2-REFACTOR-PLAN.md must contain:

## Objective
## Current Architecture Assessment
## Target Architecture
## Critical Invariants
## Dependency Problems
## Migration Map
## Data Ownership Map
## Compatibility Strategy
## Milestones
## Progress
## Discoveries
## Decisions
## Validation Evidence
## Remaining Work

Every milestone must define:

- scope
- affected modules/files
- implementation strategy
- acceptance criteria
- validation commands
- compatibility/rollback notes

Allowed milestone status:

NOT_STARTED
IN_PROGRESS
COMPLETE
BLOCKED

The ExecPlan is a durable living document.
A fresh Agent must be able to resume the refactor using only Git, AGENTS.md, architecture docs and this plan.
