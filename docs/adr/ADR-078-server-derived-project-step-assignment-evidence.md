# ADR-078: Project PlanStep assignment evidence is server-derived

- Status: Accepted
- Date: 2026-09-10
- Milestone: M67-PR1-U05

## Context

The Project PlanStep assignment HTTP adapter previously accepted `capabilityHash` and
`configurationHash` from the browser. Runtime validation checked the selected AgentVersion,
Reviewer, ModelPool, Knowledge, Tool, Skill and Sandbox availability, but did not compare the
submitted capability hash with a canonical server calculation. A browser therefore could not
reliably create the required evidence and could submit an arbitrary digest.

## Decision

Runtime remains the owner of immutable PlanStep assignment evidence. The public HTTP request now
contains only semantic selections: primary Agent/AgentVersion, Reviewer Agent/AgentVersion and
ModelPool. `ProjectPlanStepAssignmentValidationApplicationApi` resolves the exact published primary
configuration through Agent public APIs, validates all referenced capabilities and the OCI Coding
boundary, and derives both the canonical capability hash and AgentVersion configuration hash.

`ProjectPlanStepAssignmentApplicationService` persists only those resolved hashes. Internal callers
may supply an expected hash for compatibility, but any mismatch fails closed with
`ASSIGNMENT_REQUIRED`; browser callers supply neither hash. Worker-created defaults and Handoff
overrides use the same resolution path.

## Consequences

- React never owns or computes authorization/capability evidence.
- A stale or tampered capability/configuration digest cannot enter a new assignment revision.
- Existing immutable assignment rows and Flyway history do not change.
- Assignment creation still performs no Provider, Tool, Git or Sandbox effect.
