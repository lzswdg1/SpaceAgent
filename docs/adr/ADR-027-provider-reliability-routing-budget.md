# ADR-027: Provider Reliability, Routing and Budget Authority

- Status: Accepted / Implemented through M27-PR3
- Date: 2026-08-23

Java Inference and PostgreSQL own Provider health, candidate routing, ModelCall attempts,
prices, quota/budget reservations and settled cost. Redis, browsers and TypeScript do not.

Health probes are read-only and may be reclaimed after an expired fenced lease. Provider
effects are different: every routing candidate uses a stable ModelCallLedger attempt and
only a known-safe terminal failure may advance. UNKNOWN never triggers automatic fallback.

Routing snapshots must be deterministic and durable-input-derived. Cost routing requires
an active immutable price. An enabled Organization budget reserves worst-case capacity
before dispatch, settles actual usage, releases known no-charge failures and retains UNKNOWN
reservations. Missing evidence fails closed rather than inventing cost.
