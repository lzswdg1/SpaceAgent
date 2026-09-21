# ADR-038: Admin Cleanup and Command Operations Center

- Status: Accepted / implemented in M45-PR1
- Date: 2026-08-27

## Decision

- Identity owns one bounded read projection over durable User and Organization Cleanup Job/Step
  tables. It exposes state counts, BLOCKED error-code aggregation, paginated jobs and exact detail.
- Admin owns paginated reads of its own command journal with state/operation/target filters.
- UNKNOWN commands may use the existing command-ID reconciliation operation. Cleanup BLOCKED/UNKNOWN
  evidence is read-only: the browser receives no generic retry or state-edit endpoint.
- All reads are audited, exact-scope, bounded to 100 records and payload-redacted. Admin still has no
  direct platform database credential; the browser calls only `/admin/v1/**`.

## Consequences

- Operators can locate PENDING/RETRY/BLOCKED cleanup, inspect ordered progress and see aggregated
  blocker codes across User and Organization cleanup.
- Recent FAILED/UNKNOWN commands are discoverable without knowing the command ID first.
- Manual reconciliation remains evidence-driven and cannot blindly replay ambiguous Tool, Model,
  Git or cleanup effects.

## Rejected

- Admin SQL or copied cleanup tables; unbounded export; browser-owned retry; direct cleanup state
  edits; automatic UNKNOWN redispatch.
