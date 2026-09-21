# ADR-008: Organization Lifecycle Foundation

- Status: Accepted
- Date: 2026-08-22
- Scope: M13-PR1

## Context

The active Identity module already creates a personal Tenant and OWNER membership during
registration. Product Organization and technical Tenant are the same security boundary,
but the active platform has no public organization list/create/switch/member/transfer/
leave lifecycle. Archived five-service documentation contains reusable invitation and
ownership-transfer evidence, but those runtimes are not active.

## Decision

- Keep `platform_tenants` as the physical table and Tenant compatibility contract.
- Expose product-facing `OrganizationApplicationApi` and `/api/v1/organizations` routes;
  do not create a duplicate Organization table or module.
- Registration atomically provisions an unowned Tenant shell, User, creator/OWNER, and
  active membership. Product-created organizations use an existing user as creator.
- Add `creator_user_id` and `deletion_requested_at` to `platform_tenants`.
- Enforce at most one ACTIVE OWNER membership per Organization in PostgreSQL.
- Support ACTIVE Organization creation/listing, existing-user membership binding and
  role changes, member removal, ownership transfer, leave, and active-context switch.
- Switching Organization opens a new organization-scoped refresh session and JWT. The
  JWT carries both compatibility `tenant_id` and product `organization_id` with the same
  value; every request still verifies ACTIVE membership.
- OWNER may leave a single-member Organization, which suspends the last membership and
  atomically marks the Organization `DELETING`. A non-empty Organization requires owner
  transfer before the owner leaves.
- `DELETING` immediately denies new sessions and resource access. Physical resource
  cleanup and final `DELETED` transition require a later cross-module cleanup worker;
  Identity must not query or delete another module's persistence directly.
- M13-PR1 binds already-registered users by user ID. Token/email invitation acceptance is
  a later Organization onboarding increment.

## Role rules

- OWNER: all lifecycle and role operations; only OWNER can assign/remove ADMIN or
  transfer ownership.
- ADMIN: bind, update, or remove MEMBER/VIEWER users.
- MEMBER/VIEWER: read Organization/members and leave themselves.
- OWNER role is changed only by the explicit ownership-transfer use case.
- Self-removal uses leave, not the administrative remove-member operation.

## Consequences

- A user can belong to multiple Organizations while each access token has one active
  Organization context.
- User primary `tenant_id` remains the registration/default compatibility organization;
  switching does not rewrite that historical primary reference.
- Empty-organization destruction is safe, auditable, and immediately access-revoking,
  while physical cleanup remains separately implementable without cross-module DAO use.
- Existing Tenant APIs and JWT claims remain backward compatible.
