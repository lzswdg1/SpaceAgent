# ADR-034: Independent Platform Administration Control Plane

- Status: Accepted / implemented through M47-PR1
- Date: 2026-08-26
- Scope: M40 platform-level administration backend

## Context

SpaceAgent currently has Organization-scoped `OWNER/ADMIN` roles only. Normal JWTs always carry
tenant context, global User queries do not exist, login/activity timestamps are not persisted, and
Provider inventory is tenant-scoped. A highest-privilege operator must administer the whole server
without becoming a member of every Organization.

Making this operator a User in a hidden Organization would mix platform authority with tenant RBAC.
Giving a separate service direct write access to `spaceagent_platform` would bypass module ownership,
validation, cleanup, ledgers and audit.

## Decision

Create a separate Java/Spring Boot deployable, `apps/platform-admin-server`, with its own
`spaceagent_admin` PostgreSQL database.

The service owns only:

- `SystemAdministrator` credential, MFA and session lifecycle;
- platform-administration command journal;
- append-only administration audit.

A SystemAdministrator is not a platform User and has no Organization/Tenant membership or claims.
Admin tokens have a distinct issuer/audience/key and are rejected by the user plane.

`platform-admin-server` never connects to `spaceagent_platform` and receives no Provider/MCP
decryption key. It calls versioned private endpoints on `platform-server` using mTLS plus a
short-lived, exact-scope service JWT carrying the administrator actor and command ID.

`platform-server` remains authoritative. Each module exposes redacted SystemAdministration read
APIs and idempotent commands for data it owns; the internal HTTP adapter never accesses module
repositories directly.

Provider/API-key administration is inventory and write-only rotation/revocation. Plaintext and
ciphertext are never returned. Base URLs remain visible after existing validation.

Regular User deletion is suspension plus a durable, module-owned, lease/fenced cleanup workflow.
Sole Organization ownership, shared-resource ownership, running leases and UNKNOWN effects block
final deletion until explicitly resolved. Direct cascade deletion is forbidden.

## Consequences

- Admin service can deploy/restart independently; its outage does not block the user plane.
- The platform gains a second database, but not a second business-state authority. Admin DB backup
  cannot restore or rewrite tenant data.
- `platform-server` requires small internal read/command surfaces and Identity activity persistence;
  a completely zero-change data plane is not viable without unsafe direct SQL.
- Highest privilege remains powerful but does not imply secret retrieval or silent impersonation.
- Admin Web is a later client of this backend and owns no authorization truth.

## Rejected

- hidden SYSTEM Organization and tenant-role escalation;
- direct Admin SQL against the platform database;
- normal API impersonation tokens;
- plaintext Provider/API-key retrieval;
- immediate user cascade delete;
- interpreting unexpired sessions as online activity.

Detailed API, schema, lifecycle and delivery phases are defined in
`docs/architecture/PLATFORM-ADMIN-CONTROL-PLANE.md`.

M42-PR1 extends this decision with Identity-owned Organization create/update/decommission commands
and owner-filtered redacted Provider/Agent reads. See ADR-035. The independent database, secret
redaction, recent-MFA, exact-scope and no-impersonation boundaries remain unchanged.

M43-PR1 adds the browser double-submit CSRF recovery transport in ADR-036. Refresh remains bound to
the HttpOnly credential and durable Session hash; no Admin credential moves into Web Storage.

M44-PR1 adds Identity-owned Organization Membership/OWNER administration in ADR-037. Admin remains
outside every Organization and resolves ownership only through explicit audited commands.

M45-PR1 adds bounded Cleanup/Command operations-center reads in ADR-038. Cleanup remains owner-state
evidence and exposes no generic retry; UNKNOWN commands retain command-ID reconciliation only.

M46-PR1 adds owner-module per-User resource/effect drill-down in ADR-039. Admin receives only
redacted metadata through one exact scope and gains no content, credential, impersonation, direct
business persistence or effect-retry authority.

M47-PR1 adds Admin-DB-only principal lifecycle, forced password change, TOTP/recovery-code
provisioning and Session revocation in ADR-040. Raw provisioning remains first-response-only.
