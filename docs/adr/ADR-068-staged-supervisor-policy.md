# ADR-068: Staged Provider-backed Supervisor Policy

- Status: Accepted
- Date: 2026-09-08
- Milestone: M59-PR2-U01

Supervisor policy is immutable for a running graph and has four modes: deterministic, shadow, opt-in and
selected default. Shadow records comparison evidence only and never executes a second side effect. A kill
switch always selects deterministic policy for new runs. Provider UNKNOWN, budget exhaustion or invalid
commands fail closed; they never retry or fall back into another decision. Java keeps all Provider/Budget/
Ledger authority, while TypeScript remains ephemeral and credential-free. Persistence, metrics and rollout
execution are intentionally deferred to later M59-PR2 units.

## M65-PR4-U06 final remediation

`SHADOW` now runs a second, read-only candidate path after the deterministic command has passed scope
validation. The candidate may cross exactly one Provider boundary, but that boundary remains Java-owned and
therefore resolves through ModelPool, Budget and ModelCallLedger before the ephemeral TypeScript supervisor
receives the result. Candidate commands are validated and hashed only for comparison: they are never recorded
as accepted commands, dispatched through Project/Collaboration APIs, or allowed to advance the authoritative
Run cursor.

The comparison uses command kind plus a canonical, recursively key-ordered payload hash. It emits only bounded
mode/outcome metric tags and matching OpenTelemetry span attributes. Provider failure, malformed correlation,
invalid scope, recursive model requests, or unavailable configuration are classified as `UNKNOWN_CANDIDATE`;
none of those outcomes changes or retries the deterministic main-path result.
