# ADR-035: Platform Organization Administration and Owner-Scoped Resource Reads

- Status: Accepted / implemented in M42-PR1
- Date: 2026-08-26
- Scope: backend-only Platform Administration extension

## Context

M40 implemented global Organization inventory and User administration, but a
`SystemAdministrator` could not create, rename or deliberately retire an Organization. User detail
also exposed memberships only; support operators could not inspect the redacted Providers or Agent
identities owned by one User without scanning unrelated global inventories.

Giving `platform-admin-server` direct SQL or a Provider key would violate ADR-034. Reusing tenant
APIs by impersonating an Organization owner would also mix platform authority with tenant RBAC.

## Decision

- Identity remains the only Organization lifecycle owner. A new owner-module administration API
  creates an ACTIVE Organization with one existing ACTIVE User as its unique OWNER, updates only
  name/slug metadata, and requests deletion.
- Platform-administrator deletion is explicit decommissioning. Identity atomically changes ACTIVE
  to DELETING and enqueues the existing ADR-025 durable Organization CleanupJob. It never cascades
  business rows in the HTTP transaction and it does not make the administrator a member.
- Create/update/delete use the existing dual idempotent command journals, mandatory reason, recent
  MFA, exact-scope service JWT and UNKNOWN reconciliation behavior.
- Inference exposes a bounded Provider page filtered by `ownerUserId`; the existing redacted
  Provider projection is reused and no secret/ciphertext is added.
- Agent exposes a bounded owner-filtered Agent identity page with lifecycle, current-version
  reference and active key count. It does not expose AgentVersion system prompts or API-key values.
- Integration composes only public owner Application APIs. `platform-admin-server` continues to
  hold no `spaceagent_platform` credential or platform decryption key.

## Consequences

- Explicit Organization deletion immediately revokes Organization accessibility through DELETING;
  current members lose that tenant context while global User identities remain.
- The existing cleanup retention, Runtime lease drain, fencing, retries, BLOCKED evidence and
  minimal Organization tombstone semantics apply unchanged.
- User-scoped Provider/Agent reads are support projections only. They cannot mutate resources or
  become authorization/recovery truth.
- The legacy internal command wire field `targetUserId` remains for rolling compatibility;
  external Admin command responses additionally expose generic `targetType` and `targetId`.

## Rejected

- Admin-service SQL against tenant, Provider or Agent tables;
- hidden Organization membership or User impersonation;
- synchronous Organization cascade delete;
- returning Provider secrets, Agent keys or AgentVersion prompts;
- unbounded per-user resource aggregation.
