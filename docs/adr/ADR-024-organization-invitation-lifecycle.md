# ADR-024: Organization Invitation Lifecycle

- Status: Accepted
- Date: 2026-08-23
- Scope: M25-PR1 (backend only)

## Context

M13-PR1 made Tenant the product Organization security boundary and supports binding an
already-registered user by ID. The requested onboarding flow also needs a user to join an
Organization through a transferable invitation. The active backend had no invitation
aggregate, durable expiry/revocation state, secure token handling or acceptance endpoint.

Physical cleanup of an empty Organization is related to lifecycle but crosses every
resource owner and has materially different retention and failure semantics. Combining it
with invitation onboarding would make Identity responsible for other modules' persistence.

## Decision

- Identity owns `OrganizationInvitation`, its repository, Application API and
  `platform_organization_invitations`.
- Keep Organization as `platform_tenants` and Membership as
  `platform_tenant_memberships`; invitation acceptance activates that existing Membership
  aggregate and creates no parallel authorization state.
- Generate 32 cryptographically random bytes and return the URL-safe plaintext token only
  from creation. Persist only its SHA-256 digest. The domain and repository ports cannot
  carry a plaintext token.
- Normalize invited email consistently with email-style registered identities. Acceptance
  requires an authenticated user whose authoritative external identity exactly matches it.
- Public preview is the only unauthenticated invitation operation. It uses POST so the token
  is not a URL path/query value and returns only Organization name/slug, masked email, role,
  effective status and expiry.
- OWNER may invite ADMIN/MEMBER/VIEWER. ADMIN may invite MEMBER/VIEWER. OWNER assignment
  remains exclusive to ownership transfer.
- Reject an already-active member, a second pending invitation for the same
  Organization/email, invalid/expired/revoked tokens and all replays. Suspended Membership
  may be reactivated through a valid invitation.
- PostgreSQL row locking plus a conditional PENDING-to-ACCEPTED update make acceptance
  single-winner under concurrent requests. Membership activation and invitation transition
  share the Application transaction. Mutations lock Organization before Invitation; read
  paths compute expiry from durable `expires_at` without rewriting a concurrent terminal
  state. An expired row is persisted as EXPIRED only while creating its replacement under
  those same locks.
- V1025 is additive and skips safely for historical partial Flyway baselines that do not
  contain Identity tables.

## Consequences

- Clients must capture and deliver the creation token through a trusted channel; email
  delivery and frontend UX are deliberately outside this backend milestone.
- Registration still provisions a personal Organization. Accepting another Organization
  does not silently remove the user from the personal one; explicit leave drives the empty
  Organization lifecycle.
- M25-PR2 must separately define retention classes, deletion blockers, per-owner cleanup
  contracts, durable retries/idempotency and the final DELETED transition. Identity must not
  delete Project, Agent, Provider, Runtime, Memory, Artifact, audit or object-store state
  through cross-module repositories.
