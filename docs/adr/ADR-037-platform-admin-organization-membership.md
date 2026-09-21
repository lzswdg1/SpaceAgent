# ADR-037: Platform Administrator Organization Membership Control

- Status: Accepted / implemented in M44-PR1
- Date: 2026-08-27
- Scope: Admin Organization membership and unique OWNER lifecycle

## Context

M42 added Organization entity administration, but a SystemAdministrator could not inspect the
complete Membership list, add/reactivate a member, change a role, remove a member or resolve OWNER
blockers explicitly. Reusing tenant APIs would require impersonating a User/OWNER, and direct Admin
SQL would bypass Identity invariants and audit.

## Decision

- Identity remains the only owner of Organization, Membership and unique OWNER state.
- A bounded owner-module query returns ACTIVE and SUSPENDED Memberships with User identity/status,
  role, joined/updated evidence, search and role/status filters.
- Add/reactivate accepts only an existing ACTIVE User and `ADMIN | MEMBER | VIEWER`. An already
  ACTIVE Membership conflicts instead of silently changing role.
- Role update applies only to an ACTIVE non-OWNER Membership. `OWNER` can never be assigned or
  removed through a generic member endpoint.
- Remove suspends a non-OWNER Membership; it does not delete User identity or history.
- OWNER transfer is one explicit atomic command. The current OWNER becomes ADMIN, the selected
  ACTIVE member becomes OWNER, and `creator_user_id` changes in the same Identity transaction. The
  existing unique partial index remains the database backstop for exactly one ACTIVE OWNER.
- Admin mutations retain recent MFA, reason, dual idempotent journals, exact-scope service JWT,
  append-only audit and UNKNOWN reconciliation. Admin never becomes an Organization member and no
  impersonation token is issued.
- Existing request authorization and refresh rotation revalidate current Membership and role, so a
  suspended or role-changed member cannot continue using stale Organization authority.

## Consequences

- User-deletion ownership blockers can be resolved explicitly without guessing a successor.
- Pre-existing Refresh/Access material does not preserve removed or old OWNER authority because
  the platform authorization filter compares every request with current Identity Membership.
- No Flyway migration or second membership store is required.
- Admin Web remains a client of `/admin/v1/**` only and uses bounded member pages rather than a
  client-side global User scan.

## Rejected

- silent User impersonation;
- Admin SQL against `platform_tenant_memberships`;
- assigning OWNER through generic role update;
- automatic successor selection;
- hard-deleting Membership history;
- unbounded Organization member responses.
