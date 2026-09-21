# Administrator business evidence, presence and resource accounting

## Decision

Extend the existing singleton, non-tenant administration control plane; do not create a second
business authority. Identity owns user lifecycle, credentials, access revocation and presence.
Governance owns durable redacted business audit evidence; Integration translates authenticated
HTTP attempts/results and composes owner queries. Inference/Runtime/Tooling/Project retain their
ledgers and resource facts. Admin Server accesses only scoped private wire APIs and persists only
its own administrator security, command and audit state. All frontends are frozen by user request.

User changes use recent-MFA, reason-bearing idempotent commands. Password-reset material is hashed
at rest, short-lived and one-response-only, never replayable from command/audit records; consuming
it revokes prior sessions. Login identity changes and session revocation must invalidate access
tokens as well as refresh tokens. Compatibility tokens are accepted only before explicit revocation.

Online means an unexpired authenticated client-instance heartbeat lease whose user/membership and
authorization remain valid. It is not a count of sockets, devices, refresh-token rows or five-minute
activity. Global users deduplicate across instances. Query responses declare coverage; no heartbeat
clients are added in this backend-only change. Server clocks and ownership checks bound lease updates.

Business audit captures safe route/resource identity, actor/organization, correlation and outcome,
not bodies, names containing sensitive content, credentials or host paths. A durable admitted attempt
must remain discoverable if completion cannot be recorded; HTTP success/acceptance must not be
confused with asynchronous effect completion. Ledger and audit evidence remain immutable to Admin.

Usage aggregates are computed from owner-ledger facts across all outcomes, with explicit scope,
units, observation windows, successful/failed/uncertain partitions and missing-price/usage coverage.
CPU, memory, network and storage values require real observations; unsupported/failed collection is
nullable with a reason, never zero. Worker observations carry no business authority and are bound to
the Java-admitted execution identity. Storage walkers remain confined to owner-resolved managed paths,
do not follow symlinks and expose no filesystem path or contents. Historical gaps remain visible.

Implementation and exact validation boundaries: `.agent/ADMIN-BACKEND-COMPLETION-PLAN.md`.
