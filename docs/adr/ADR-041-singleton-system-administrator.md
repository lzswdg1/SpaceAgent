# ADR-041: Singleton SystemAdministrator Authority

- Status: Accepted / implemented in M48-PR1
- Date: 2026-08-30

## Context

M47 introduced a general administrator-principal lifecycle. The product requirement is narrower:
there is exactly one highest-privilege `SystemAdministrator`, it belongs to no Organization, and it
is the sole human principal for the independent monitoring and control plane. Treating that identity
as a collection creates unnecessary privilege-proliferation and last-administrator failure modes.

The singleton still needs safe credential recovery. Re-running bootstrap, modifying SQL, retaining
raw secrets, or allowing an authenticated principal to reset its own TOTP without its current
password would weaken the independent Admin security boundary.

## Decision

Admin V4 adds a database-enforced `singleton_slot = 1` unique constraint. Migration refuses to
proceed when more than one Admin principal already exists. Bootstrap remains a zero-to-one,
one-time-only operation and now requires eight unique recovery codes whose hashes are stored.

Online administrator creation, suspension, restoration and credential-recovery compatibility
routes fail closed with `ADMIN_SINGLETON_PRINCIPAL_ENFORCED`. The singleton can list and revoke its
other Sessions but cannot revoke its current Session. It may rotate all eight recovery codes only
after recent MFA and current-password verification; plaintext codes are returned once and only
their hashes are durable.

Lost-credential recovery is an offline operator action. A startup-only break-glass configuration
requires the existing login, a unique request ID, the exact confirmation phrase, a policy-compliant
new password, a new Base32 TOTP Secret and eight recovery codes. It increments credential version,
reactivates the singleton, revokes every Session, marks forced password change, replaces recovery
codes and records a secret-free audit plus a durable request-hash replay fence. Bootstrap and
break-glass cannot be enabled together, and an applied request cannot be replayed.

The singleton is still not a tenant User or Organization member. Business reads and mutations
continue through exact-scope platform Application APIs; `spaceagent_admin` never becomes a copy of
business persistence.

## Consequences

- The Admin database can contain zero principals only before first bootstrap, and at most one
  principal thereafter; normal operation requires exactly one.
- M47 multi-principal provisioning and lifecycle success contracts are superseded. Compatibility
  HTTP routes remain temporarily mapped but can only return the singleton conflict.
- Operators must store bootstrap/break-glass material outside Git, clear it immediately after a
  successful start, and retain recovery codes in a separate secure system.
- A future read-only auditor or multi-person approval model requires a new principal/permission
  architecture and migration; it must not be represented as a second `PLATFORM_SUPER_ADMIN`.
- This backend-only milestone does not update the existing Admin Web lifecycle controls; until a
  later UI cleanup they receive the authoritative conflict response.
