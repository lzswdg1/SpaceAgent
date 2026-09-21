# ADR-039: Admin User Resource Drill-down

- Status: Accepted / implemented in M46-PR1
- Date: 2026-08-27

## Context

M42 exposed owner-filtered Provider and Agent records, but support operators could not inspect the
rest of one User's durable resource topology. Copying platform rows into `spaceagent_admin` or
querying `spaceagent_platform` directly would create a second business read authority. Returning
Conversation titles, Knowledge names/content, Memory keys/values, Automation prompts, Runtime
payloads, Tool arguments/results, filesystem paths or credential material would also exceed the
administration support boundary.

## Decision

Each owning module exposes a bounded read-only SystemAdministration projection. Integration routes
the exact requested kind and composes only counts for the overview. The independent Admin service
uses the exact `system-admin:users:resources:read` workload scope, audits the public read and stores
no returned business rows.

Supported kinds are `MODEL_POOL`, `PROJECT`, `TASK`, `WORKSPACE`, `CONVERSATION`,
`MCP_CONNECTION`, `KNOWLEDGE_DOCUMENT`, `MEMORY`, `AUTOMATION`, `RUN`, `MODEL_EFFECT` and
`TOOL_EFFECT`. Association means the User is the explicit owner/manager/actor in the owner table;
Task follows its owner Project, and USER Memory excludes Project/Task content owned by other scopes.

The common wire contains only IDs, Organization/parent references, an optional non-content display
name, state, relation type, timestamps, bounded counts and allowlisted safe error codes. Conversation
titles, Knowledge names/storage, Memory content/key, Automation description/prompt/error text,
Runtime failure text, Provider response/error summaries, Tool arguments/results/errors, MCP endpoint
and encrypted authentication are excluded. Model/Tool risk effects are evidence-only and add no
retry, reconciliation or mutation capability.

## Consequences

- `platform-admin-server` remains independent of platform business persistence and secret keys.
- Every page is capped at 100 and invalid kinds fail closed.
- Overview cost is a fixed bounded fan-out over owner APIs, never a per-row browser fan-out.
- No Flyway migration, duplicated Admin business table, impersonation or new mutation endpoint is
  introduced.
