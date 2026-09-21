# ADR-030: Trusted Beta Release Boundary

- Status: Accepted
- Date: 2026-08-23
- Scope: M30-PR1

## Context

SpaceAgent has a production-oriented Java/PostgreSQL backend and durable Coding Runtime, but
the current Sandbox Worker/in-process command boundary is not a container or VM security
boundary. A public multi-tenant claim would be unsafe. At the same time, private deployments
and invite-only users working on trusted repositories can use the completed backend if
deployment, recovery and operational gates become reproducible.

## Decision

The first release is `TRUSTED_BETA`, API/CLI-first and backend-only. It requires PostgreSQL,
real inference/embedding adapters, non-development secrets, a persistent writable managed
Workspace root, readiness at schema V1034, verified backup/restore and the golden-path
restart regression. Coding execution is limited to trusted repositories. A configuration
requesting public untrusted-code execution fails startup.

TLS terminates at a trusted reverse proxy/load balancer; platform-server honors forwarded
headers, uses graceful shutdown and publishes separate liveness/readiness groups. Remote
automatic merge, billing, frontend parity, public sandbox isolation and the remaining target
blueprint are post-release iterations.

## Consequences

- M30 can produce an honest deployable Beta without waiting for every target feature.
- Operators receive executable preflight, backup/restore verification and runbooks.
- Public multi-tenant Coding remains blocked until a separately audited hardened sandbox.
