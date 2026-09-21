# ADR-040: Administrator Principal Lifecycle and Recovery

- Status: Superseded for principal cardinality/lifecycle by ADR-041; recovery primitives retained
- Date: 2026-08-27

## Context

The independent Admin plane previously depended on one bootstrap-created
`PLATFORM_SUPER_ADMIN`. It could not create or suspend another administrator, rotate a lost TOTP
factor, issue recovery codes, force a password replacement, inspect Sessions or revoke another
Session. Re-running bootstrap, editing SQL or sharing one account would bypass audit and create an
unrecoverable operational dependency.

## Decision

`platform-admin-server` and `spaceagent_admin` remain the sole authority. V3 adds
`must_change_password` and hashed one-use `admin_recovery_codes`. The existing java-totp library
generates Base32 TOTP Secrets and eight recovery codes; PostgreSQL stores only encrypted TOTP,
BCrypt password hashes and SHA-256 hashes of high-entropy recovery codes.

Creating or recovering a principal generates a temporary password, new TOTP Secret and recovery
codes. They appear only in the first successful HTTP response and never enter command/audit JSON.
The principal may complete password+TOTP/recovery-code login, but durable Session authorization
allows only `me`, password change and logout until the password changes. Password change increments
credential version and revokes every Session, requiring a fresh login.

All lifecycle mutations require a current `PLATFORM_SUPER_ADMIN`, recent TOTP, reason and an
idempotency key. Create IDs are stable for the actor+key so replay returns the principal without
replaying provisioning material. Suspend revokes Sessions; restore never restores them. Credential
recovery replaces password/TOTP/recovery codes, increments credential version and revokes Sessions.
Recovery-code login consumes exactly one code. Self-suspension, self-credential-reset, current
Session revocation and removal of the last ACTIVE administrator fail closed.

M47 keeps one role. A future `PLATFORM_AUDITOR` needs a separate permission matrix and ADR; it is not
emulated by an ACTIVE super-admin with hidden UI controls.

## Consequences

- Bootstrap remains one-time initialization, not an account-management mechanism.
- No raw password, TOTP Secret, recovery code, refresh token or CSRF token is persisted or audited.
- Admin credential recovery does not touch platform Users, Organizations or `spaceagent_platform`.
- Production private-entry HTTPS/mTLS/VPN/IP-Allowlist acceptance remains an operator environment
  gate outside deterministic M47 tests.
