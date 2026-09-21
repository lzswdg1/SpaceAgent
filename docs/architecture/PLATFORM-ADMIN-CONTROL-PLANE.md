# SpaceAgent Platform Administration Control Plane

> Date: 2026-08-26
> Milestone: M40-PR1 through M48-PR1 Singleton SystemAdministrator
> Status: M48-PR1 SINGLETON SYSTEMADMINISTRATOR IMPLEMENTED
> Scope: Admin identity/security transport and independent Admin Web

## 1. Outcome

SpaceAgent will add an independently deployable Java service:

```text
apps/platform-admin-server
```

It is the platform-level administration control plane for a small set of highest-privilege
operators. A platform administrator is a distinct `SystemAdministrator` principal:

- it is not a `platform_users` row;
- it has no Organization/Tenant membership;
- its token contains no `tenant_id`, `organization_id`, or `tenant_role`;
- it cannot use normal tenant APIs or impersonate a user by default;
- normal User/OWNER/ADMIN tokens can never call administration APIs.

Independent deployment does not create a second authority over platform business data.
`platform-admin-server` owns administrator identity, MFA/session, command journal and audit only.
The existing `platform-server` remains the only writer of User, Organization, Provider, Agent,
Project, Runtime and Tooling state.

## 2. Baseline gaps and current status

- Normal access tokens are issued with role `USER` plus Organization claims. Organization
  `OWNER/ADMIN` is tenant-scoped and is not a platform administrator.
- M40-PR3 adds a separate Identity SystemAdministration API for global paginated users and
  Organizations; ordinary tenant APIs remain scoped.
- V1036 adds User lifecycle status plus `last_login_at`/`last_seen_at`; V1037 adds activation and
  command evidence; V1038 adds fenced User cleanup Job/Steps and tombstone execution.
- Refresh-token rows prove unexpired sessions, not whether a user is currently active.
- Provider APIs remain tenant-scoped. M40-PR3 adds a private cross-tenant redacted Provider,
  Agent-key and MCP inventory without plaintext or ciphertext.
- User deletion cannot be a direct SQL cascade: at least 23 foreign-key references and external
  Git/Workspace/secret effects cross module ownership.
- Current tenant Observability cannot be reused as an unrestricted platform-wide admin endpoint.

## 3. Goals and non-goals

### Goals

- Separate platform-super-admin login and session lifecycle outside Organization identity.
- Platform dashboard with user/login/activity/resource totals and freshness evidence.
- Paginated global User and Organization inventory.
- Redacted Provider/API-key/Base URL, Agent key and MCP credential inventory.
- Create, suspend, restore and safely request deletion of regular users.
- Complete, append-only audit and idempotent command evidence.
- Independent deploy/restart/failure boundary: admin downtime does not stop the user plane.

### Non-goals for the first implementation

- Admin frontend, billing, subscriptions, support tickets or arbitrary SQL console.
- Reading/decrypting plaintext Provider, Agent API-key or MCP secrets.
- Silent user impersonation or bypassing Organization/resource ownership.
- Immediate cascading hard delete of a user and every related row.
- Direct Git, Tool, Provider or Sandbox effects from the admin service.
- Making the admin database a replica or source of truth for platform business data.

## 4. Physical topology

```text
Future Admin Web / Admin CLI
            |
            | admin session / short-lived admin access token
            v
  platform-admin-server  (private management ingress)
      |             |
      |             +--> PostgreSQL: spaceagent_admin
      |                    administrators / MFA / sessions
      |                    command journal / audit
      |
      | mTLS + short-lived service JWT
      | audience=spaceagent-platform-admin-internal
      v
  platform-server /internal/system-admin/v1/**
      |
      +--> module-owned SystemAdministration Application APIs
      +--> PostgreSQL: spaceagent_platform
      +--> existing cleanup/Runtime/Git/secret owner boundaries
```

Deployment rules:

- `platform-admin-server` is a separate image, port, health/readiness contract and release unit.
- It is not included in default public Compose. An optional `admin` profile may run it locally.
- Production ingress is restricted by VPN/private network and IP allowlist. No route is exposed
  through the normal user-facing proxy.
- `spaceagent_admin` uses a distinct database credential. That credential has no access to
  `spaceagent_platform`.
- `platform-admin-server` has no Provider/MCP encryption key and cannot decrypt platform secrets.
- `platform-server` accepts admin-internal calls only through a dedicated service identity; the
  current generic internal token is not sufficient for platform-wide administration.

### 4.1 Technology and internal module structure

Use the repository's Java baseline and mature infrastructure:

- Java 21 + Spring Boot 3.5;
- Spring Security for isolated Admin authentication, authorization and OAuth2/JWT validation;
- a maintained TOTP or WebAuthn implementation rather than custom cryptography;
- PostgreSQL + Flyway for administrator/session/command/audit durability;
- Spring HTTP Interface/RestClient with bounded pools/timeouts for the private platform client;
- Actuator/Micrometer for private health and operational telemetry.

The service is a modular backend, not a thin controller around SQL:

```text
com.spaceagent.admin
├── identity       SystemAdministrator / credential / MFA / session
├── command        idempotent command journal and reconciliation
├── audit          append-only administration evidence
├── dashboard      bounded projection composition/cache metadata
├── platformclient versioned private platform contract adapter
├── security       Admin session and mTLS/service-JWT boundaries
└── integration    /admin/v1 HTTP adapters and private health
```

It may reuse minimal technical primitives, but must not depend on the `platform-server` Maven
artifact, business-domain classes or persistence implementations. Wire models live under
`contracts/platform-admin/v1` and are independently versioned.

## 5. Administrator identity and authorization

### 5.1 Principal

```text
SystemAdministrator
├── id
├── loginName
├── displayName
├── status: ACTIVE | SUSPENDED | LOCKED | DELETED
├── role: PLATFORM_SUPER_ADMIN
├── credentialVersion
├── mfaRequired = true
├── createdAt / updatedAt
└── lastSuccessfulLoginAt
```

Admin V4 permits exactly one `PLATFORM_SUPER_ADMIN` principal and enforces that cardinality in
PostgreSQL. Future read-only roles such as `PLATFORM_AUDITOR` require a later ADR and a different
principal model; they cannot be represented as another super-admin. Scopes remain explicit so
controllers do not check a magic username.

### 5.2 Authentication

- No public registration endpoint.
- The singleton administrator is created by one-time bootstrap; it writes only password/recovery
  hashes and an encrypted TOTP Secret, then bootstrap mode must be disabled and cleared.
- Additional administrators are prohibited by Admin V4. Online lifecycle compatibility routes
  fail with `ADMIN_SINGLETON_PRINCIPAL_ENFORCED`.
- Spring Security owns authentication; credentials use an adaptive password hash and mandatory
  TOTP or WebAuthn/passkey MFA through a mature implementation.
- Admin browser refresh credentials are random, stored hashed in `spaceagent_admin` and transported
  only by an HttpOnly, Secure, SameSite=Strict cookie. M43-PR1 adds a separate readable
  SameSite=Strict CSRF cookie; refresh/logout require that cookie, the matching header and the
  durable CSRF hash. Access/refresh credentials remain absent from Web Storage. Admin CLI may
  exchange MFA for a short-lived bearer.
- Session rotation, replay rejection, login throttling and all-session revocation are durable.
- Destructive commands require recent reauthentication/MFA, not merely a valid old session.

### 5.3 Token separation

Admin tokens use a separate issuer, signing key and audience. `platform-server` public security
rejects them. Normal platform JWTs and Organization roles are rejected by `platform-admin-server`.

Admin-internal service JWT claims are bounded:

```text
iss=platform-admin-server
aud=spaceagent-platform-admin-internal
sub=<admin-service-instance>
actor_id=<SystemAdministrator ID>
command_id=<UUID when mutating>
scope=<exact internal operation>
iat/exp <= 60 seconds
```

mTLS authenticates the calling workload; the JWT binds actor and operation evidence.

## 6. Data ownership

### 6.1 `spaceagent_admin`

| Table | Owner/purpose |
| --- | --- |
| `admin_principals` | Administrator identity/status/role only |
| `admin_credentials` | Password hash and credential version |
| `admin_mfa_factors` | Encrypted TOTP/WebAuthn references and recovery state |
| `admin_sessions` | Hashed opaque sessions, expiry, rotation and revocation |
| `admin_login_attempts` | Bounded security evidence and lockout counters |
| `admin_commands` | Idempotency key, operation, target, state and platform correlation |
| `admin_audit_events` | Append-only actor/action/reason/outcome/hash evidence |

No table contains tenant-owned Provider/API keys, messages, prompts, documents or copied platform
business rows.

### 6.2 `spaceagent_platform` M40 additions

Identity owns; the status/activity/auth-event subset is implemented by V1036:

- `platform_users.status`: `PENDING_ACTIVATION | ACTIVE | SUSPENDED | DELETION_PENDING | DELETED`;
- `must_change_password`, `deletion_requested_at`, `deleted_at`;
- `platform_user_activity` with `last_login_at`, throttled `last_seen_at`, login count and bounded
  client metadata;
- append-only `platform_auth_events` with retention, success/failure code and privacy-preserving
  IP/User-Agent hashes;
- durable `platform_user_cleanup_jobs` and 15 ordered owner Steps are implemented by V1038.

V1037 adds Identity-owned hashed one-time `platform_user_activation_tokens` and a platform-side
`platform_identity_admin_commands` idempotency journal. Activation plaintext is never persisted in
either database and is absent from replay/reconciliation responses.

V1036 adds nullable non-secret Provider inventory fields for future secret write/rotation:

- `secret_hint` such as a bounded vendor prefix/last-four display;
- keyed HMAC `secret_fingerprint` for identifying duplicate/configured credentials;
- encryption key version.

Existing encrypted ciphertext is never returned. Legacy rows show `secretConfigured=true` and
`secretHint=null` until rotated; there is no decryption-based backfill into the admin database.

Applied Flyway migrations through V1036 remain immutable. M40-PR4 adds forward-only V1037.
M40-PR5 adds forward-only V1038; neither earlier migration is rewritten.

## 7. Platform-owned internal administration APIs

Every owning module exposes a narrow public Java Application API. The internal HTTP adapter may
compose responses but never imports repositories.

| Owner | Required API |
| --- | --- |
| identity | global User/Organization page, activity summary, create/suspend/restore, deletion preflight/job |
| inference | redacted global Provider/ModelPool inventory and counts |
| agent | global Agent count and Agent API-key prefix/revocation inventory |
| project | Project/Workspace counts and user-deletion blockers/cleanup participant |
| runtime | active/completed/failed/UNKNOWN Run summary, user quiesce/cleanup participant |
| conversation | Conversation/message counts and user cleanup participant |
| memory/knowledge | user-private inventory and cleanup participants |
| tooling | MCP credential-configured inventory, grants/ledger counts and cleanup participant |
| automation/governance/artifact | counts, blockers and cleanup participants |

Internal HTTP contract, versioned under `contracts/platform-admin/v1`, begins with:

```text
GET  /internal/system-admin/v1/overview
GET  /internal/system-admin/v1/users
GET  /internal/system-admin/v1/users/{userId}
GET  /internal/system-admin/v1/users/{userId}/providers
GET  /internal/system-admin/v1/users/{userId}/agents
POST /internal/system-admin/v1/users
POST /internal/system-admin/v1/users/{userId}/suspend
POST /internal/system-admin/v1/users/{userId}/restore
POST /internal/system-admin/v1/users/{userId}/deletion-requests
POST /internal/system-admin/v1/users/{userId}/deletion-jobs
GET  /internal/system-admin/v1/users/{userId}/deletion-jobs/current
GET  /internal/system-admin/v1/commands/{commandId}
GET  /internal/system-admin/v1/credential-inventory
GET  /internal/system-admin/v1/organizations
POST /internal/system-admin/v1/organizations
PATCH /internal/system-admin/v1/organizations/{organizationId}
POST /internal/system-admin/v1/organizations/{organizationId}/deletion-jobs
GET  /internal/system-admin/v1/organizations/{organizationId}/members
POST /internal/system-admin/v1/organizations/{organizationId}/members
PATCH /internal/system-admin/v1/organizations/{organizationId}/members/{userId}
DELETE /internal/system-admin/v1/organizations/{organizationId}/members/{userId}
POST /internal/system-admin/v1/organizations/{organizationId}/ownership-transfers
GET  /internal/system-admin/v1/cleanup-jobs
GET  /internal/system-admin/v1/cleanup-jobs/overview
GET  /internal/system-admin/v1/cleanup-jobs/{kind}/{subjectId}
GET  /internal/system-admin/v1/mcp-registry/sync-jobs
POST /internal/system-admin/v1/mcp-registry/sync-jobs
GET  /internal/system-admin/v1/mcp-registry/candidates
GET  /internal/system-admin/v1/mcp-registry/candidates/{candidateId}
POST /internal/system-admin/v1/mcp-registry/candidates/{candidateId}/approvals
POST /internal/system-admin/v1/mcp-registry/candidates/{candidateId}/rejections
```

Mutations require `Idempotency-Key`, a UUID command ID, exact scope and mandatory reason. The
response returns command state and safe error codes, never exception text or secret material.

## 8. External administration API

`platform-admin-server` exposes backend endpoints for a future separate Admin Web:

```text
POST /admin/v1/auth/login
POST /admin/v1/auth/mfa/verify
POST /admin/v1/auth/mfa/reauthenticate
POST /admin/v1/auth/refresh
POST /admin/v1/auth/logout
GET  /admin/v1/me

GET  /admin/v1/dashboard?window=24h|7d|30d
GET  /admin/v1/users?page=&pageSize=&query=&status=&sort=
GET  /admin/v1/users/{userId}
GET  /admin/v1/users/{userId}/providers?page=&pageSize=
GET  /admin/v1/users/{userId}/agents?page=&pageSize=
POST /admin/v1/users
POST /admin/v1/users/{userId}/suspend
POST /admin/v1/users/{userId}/restore
POST /admin/v1/users/{userId}/deletion-requests
POST /admin/v1/users/{userId}/deletion-jobs
GET  /admin/v1/users/{userId}/deletion-jobs/current
POST /admin/v1/commands/{commandId}/reconcile

GET  /admin/v1/organizations?page=&pageSize=&query=&status=
POST /admin/v1/organizations
PATCH /admin/v1/organizations/{organizationId}
POST /admin/v1/organizations/{organizationId}/deletion-jobs
GET  /admin/v1/organizations/{organizationId}/members?page=&pageSize=&query=&status=&role=
POST /admin/v1/organizations/{organizationId}/members
PATCH /admin/v1/organizations/{organizationId}/members/{userId}
DELETE /admin/v1/organizations/{organizationId}/members/{userId}
POST /admin/v1/organizations/{organizationId}/ownership-transfers
GET  /admin/v1/credential-inventory?kind=provider|agent-key|mcp
GET  /admin/v1/audit-events?page=&pageSize=&actor=&action=&target=
GET  /admin/v1/commands/{commandId}
GET  /admin/v1/commands?page=&pageSize=&state=&operation=&target=
GET  /admin/v1/cleanup-jobs?page=&pageSize=&kind=&state=&query=
GET  /admin/v1/cleanup-jobs/overview
GET  /admin/v1/cleanup-jobs/{kind}/{subjectId}
GET  /admin/v1/mcp-registry/sync-jobs?page=&pageSize=&state=
POST /admin/v1/mcp-registry/sync-jobs
GET  /admin/v1/mcp-registry/candidates?page=&pageSize=&state=&query=
GET  /admin/v1/mcp-registry/candidates/{candidateId}
POST /admin/v1/mcp-registry/candidates/{candidateId}/approvals
POST /admin/v1/mcp-registry/candidates/{candidateId}/rejections
```

All list APIs use bounded pagination and stable ordering. Bulk export is a separately authorized,
audited future feature rather than an unbounded JSON response.

## 9. Dashboard semantics

The overview is a timestamped, redacted projection, not a recovery or billing source.

```text
User metrics
  total / pending / active / suspended / deletion-pending
  registered in selected window
  unique successful logins in 24h / 7d / 30d
  recently active users (lastSeenAt >= now - 5 minutes)
  active refresh sessions (separate from recently active users)

Resource metrics
  active/deleting Organizations
  Providers by health/auth type; ModelPools
  Agents / Projects / Conversations
  active/completed/failed/UNKNOWN Runs
  MCP Connections / active Workspaces

System evidence
  release version / schema version
  platform readiness and projection generatedAt
  counts marked stale when platform-server is unreachable
```

“Online now” is explicitly approximate and means a successful authenticated request in a bounded
recent window. It is not inferred from unexpired refresh tokens. `lastSeenAt` updates are throttled
by an Identity-owned conditional PostgreSQL update, for example at most once per five minutes.

## 10. Credential inventory

The highest-privilege operator can inspect where credentials are configured, not recover them.

Provider inventory returns:

- Provider ID, owner User and Organization;
- provider name/type, validated full Base URL and endpoint host;
- auth type, `secretConfigured`, optional masked `secretHint`, HMAC fingerprint and key version;
- enabled/default/connection status, last tested time/latency/safe error code;
- model count and ModelPool usage count.

Agent API-key inventory returns only key ID, Agent/owner/Organization, key prefix, scopes, verified/
revoked timestamps. Those keys are hash-only and cannot be shown in plaintext.

MCP inventory returns endpoint host/transport/profile and `authConfigured`; OAuth access/refresh
tokens, encrypted auth JSON, checkout grants and request headers are never returned.

No endpoint, log, audit event, trace, command payload or Admin database row contains raw keys or
ciphertext. Secret replacement is write-only and must be a separate elevated operation.

## 10.1 Official MCP Registry review

Registry synchronization is a private global operation, not a tenant-manager capability. The Admin
command path requires recent MFA, a reason, an idempotency key and the exact sync/approve/reject
scope. Synchronization returns a Job immediately; a PostgreSQL-leased Tooling worker performs the
bounded external read later. Reads expose only safe Job status and secret-sanitized Candidate/
Snapshot evidence.

Approval never contacts or writes to the upstream Registry. It accepts only active fixed-HTTPS
Streamable HTTP metadata and atomically publishes an immutable local Marketplace version. A sync
does not install a server, connect credentials, qualify capabilities or authorize Tool execution.

## 11. Regular user lifecycle

### 11.1 Create

Preferred flow:

```text
Super Admin + recent MFA + reason + Idempotency-Key
 -> Identity create PENDING_ACTIVATION User
 -> create default Personal Organization
 -> unique OWNER Membership
 -> create one-time hashed activation token
 -> return activation reference once / deliver out of band
 -> first successful activation sets credential and ACTIVE
```

No User may be created without the default Organization invariant. A target Organization binding
is a separate, explicit membership/invitation command. Generated plaintext passwords are not stored
or returned repeatedly; a temporary-password fallback must force change on first login.

### 11.2 Suspend and restore

Suspend is the immediate safety operation:

- atomically set User `SUSPENDED`;
- revoke all refresh tokens and active access-token JTIs where available;
- reject new login, refresh and authenticated requests;
- pause/fence new user-owned Automation/Runtime work through owning Application APIs;
- preserve data and Organization memberships for investigation/recovery.

Restore requires reason and audit, but does not restore expired sessions or automatically resume
dangerous work.

### 11.3 Delete

Deletion is a durable workflow, never `DELETE FROM platform_users` in an HTTP transaction.

```text
request + recent MFA + reason
 -> Identity SUSPENDED / DELETION_PENDING
 -> revoke sessions and reject new work
 -> preflight blockers
 -> claim cleanup Job with PostgreSQL lease/fencing
 -> ordered module-owned cleanup/transfer Steps
 -> retain minimal User tombstone + audit
 -> DELETED
```

Preflight blocks when the user is sole OWNER of a non-empty active Organization, has running/
UNKNOWN effects, or owns shared resources without an explicit successor. The administrator does not
become an Organization member. Resolution is one of:

- transfer Organization ownership/resources to an existing active member;
- separately request Organization deletion through the existing cleanup protocol;
- cancel the user deletion.

Suggested Steps:

```text
AUTH_FREEZE
AUTOMATION_FREEZE
RUNTIME_QUIESCE_AND_DRAIN_LEASES
OWNERSHIP_AND_MEMBERSHIP_RESOLUTION
CONVERSATION_PRIVATE_PURGE
USER_MEMORY_PURGE
KNOWLEDGE_PRIVATE_PURGE
PROJECT_PRIVATE_RESOURCE_PURGE
AGENT_PRIVATE_RESOURCE_PURGE_OR_TRANSFER
INFERENCE_PRIVATE_RESOURCE_PURGE_OR_TRANSFER
TOOLING_PRIVATE_RESOURCE_PURGE
IDENTITY_FINALIZE_USER_TOMBSTONE
```

Each Step is owned by its module, idempotent, fenced and records only safe error evidence. Shared
Organization resources survive when ownership is transferred. Git/Object effects are cleaned before
metadata. UNKNOWN effects require reconciliation and block finalization.

For the first product increment, expose create, suspend, restore and deletion preflight. Enable
physical deletion only after every cleanup participant and real restart test exists.

## 12. Command, audit and failure semantics

`admin_commands` states:

```text
RECEIVED -> DISPATCHING -> ACCEPTED | SUCCEEDED | FAILED | UNKNOWN
```

- Same idempotency key + same operation hash replays the same result.
- Same key + different input is a conflict.
- A timeout after dispatch becomes `UNKNOWN`; the Admin server queries by command ID and never
  blindly repeats create/delete/suspend.
- Platform module commands persist their own idempotency evidence transactionally with the change.
- Read failures return unavailable/stale evidence, never direct database fallback.

Every sensitive read and every mutation appends an Admin audit event containing actor, session,
action, target type/ID, mandatory reason for mutations, request/trace/command IDs, input hash,
outcome, safe error code and timestamps. It excludes passwords, tokens, prompts, document content,
Provider ciphertext, raw IP and raw User-Agent.

Audit retention is longer than session/command retention and is append-only. Audit access itself is
audited.

## 13. Security controls

- Private management ingress, mTLS, dedicated issuer/audience/key rotation and strict CSP/CORS for
  the future Admin Web.
- Mandatory MFA, recent-auth gates, login throttling, account lockout and session/device inventory.
- No wildcard scopes, tenant-header fallback, user impersonation, service-token reuse or public
  internal endpoints.
- Pagination and request/response size bounds; export disabled initially.
- Base URLs remain visible because they are non-secret configuration, but userinfo/query secrets are
  forbidden and endpoint hosts remain SSRF-validated.
- Admin server logs use allowlisted fields and never serialize request bodies by default.
- Break-glass access is time-bounded, separately keyed, disabled by default and produces a critical
  audit alert; it is not the ordinary super-admin account.

## 14. Availability and operations

- User-facing `platform-server` continues operating if Admin service or `spaceagent_admin` is down.
- Admin readiness requires its DB, signing/MFA configuration and live internal platform handshake.
- Dashboard may show a bounded cached snapshot with `generatedAt` and `stale=true`; mutations never
  use cached state.
- Platform downtime produces 503/PENDING/UNKNOWN based on dispatch evidence, never direct SQL.
- Admin service and DB are backed up/restored independently. Restoring Admin DB cannot rewrite
  platform business data.
- Actuator/metrics bind a private management interface. Audit and authentication failures have
  explicit alerts without exposing usernames or secrets as metric labels.

## 15. Migration map

| Current component | Action | Target |
| --- | --- | --- |
| Organization `OWNER/ADMIN` | KEEP tenant-scoped | never becomes platform admin |
| platform User JWT/refresh | KEEP | rejected by Admin APIs |
| no platform administrator identity | CREATE | `spaceagent_admin` SystemAdministrator/MFA/session |
| tenant Observability | KEEP | new owner APIs supply platform-wide redacted projections |
| tenant Provider view | EXTEND internally | global redacted credential inventory; no secret read |
| registration flow | REUSE through new command | admin create still provisions Personal Organization |
| no User status/activity | CREATE | Identity-owned status/auth events/activity summary |
| direct user deletion absent | CREATE incrementally | suspend -> preflight -> fenced cleanup job/tombstone |
| generic internal token | KEEP for existing narrow uses | dedicated mTLS + admin service JWT for this boundary |
| `platform-server` business repositories | KEEP authoritative | never imported by Admin service |

## 16. Rejected alternatives

1. **Make the highest user an OWNER of a hidden SYSTEM Organization.** Rejected: it leaks system
   authority into tenant RBAC and creates fake tenancy.
2. **Let Admin service read/write `spaceagent_platform` directly.** Rejected: two writers bypass
   module APIs, validation, cleanup, ledgers and audit.
3. **Reuse the normal public APIs with an impersonated tenant token.** Rejected: hidden tenancy,
   confused-deputy risk and incomplete global visibility.
4. **Return decrypted Provider/API keys to the highest administrator.** Rejected: broadens blast
   radius and violates current secret-redaction guarantees. Rotation replaces retrieval.
5. **Delete users with database cascade.** Rejected: ownership transfer, running/UNKNOWN effects,
   shared resources and Git/Object side effects require a durable protocol.
6. **Use current Refresh Token count as online users.** Rejected: expiry is not activity.

## 17. Implementation milestones

### M40-PR2 — Admin service foundation

- `apps/platform-admin-server` Java/Spring Boot module and independent Docker image;
- `spaceagent_admin` Flyway source, administrator credential/MFA/session/audit/command tables;
- one-time fail-closed bootstrap mode and dedicated issuer/audience;
- private health/readiness and no platform business access yet.

Implemented in M40-PR2. The bootstrap mode refuses to start once a principal exists, forcing the
operator to disable and clear bootstrap inputs after first creation. `java-totp` supplies TOTP;
the secret uses a separate versioned AES-GCM key. Refresh and CSRF material are hashed at rest and
rotated together. The access JWT contains administrator role/session/credential version only and
never tenant claims; each Bearer request revalidates the durable Session and credential version so
logout/revocation is immediate. A PostgreSQL integration test proves bootstrap, password+MFA,
replay rejection, refresh rotation, logout invalidation, command idempotency and audit persistence.

### M40-PR3 — Read-only platform administration

- versioned internal contract and mTLS/service JWT;
- Identity activity/auth-event schema and paginated global User/Organization views;
- dashboard/resource summaries and redacted credential inventory;
- Admin external read APIs and audit of sensitive reads.

Implemented in M40-PR3. V1036 adds default-ACTIVE User lifecycle fields, privacy-HMAC login events
and five-minute conditional `lastSeenAt`. Identity, Inference, Agent, Project, Conversation,
Runtime and Tooling each expose an owner-local Application API. The Integration adapter composes
only those APIs and accepts neither the generic internal token nor tenant JWTs: calls require a
client certificate plus an HS256 service JWT with exact read scope, actor, request ID and a lifetime
of at most 60 seconds. The Admin client is HTTPS-first, redirect-free, timeout/response-size bounded
and keeps only a five-minute process-local Dashboard fallback marked `stale=true`. User,
Organization, Provider, Agent-key and MCP reads are paginated and audited; credential ciphertext,
password hashes, OAuth tokens and content payloads never enter the wire contract or Admin database.

### M40-PR4 — User commands

- idempotent activation-based User creation with default Organization;
- suspend/restore/session revocation;
- deletion preflight and blockers;
- Admin command reconciliation and negative security tests.

Implemented in M40-PR4. Every mutation requires an Admin session whose MFA evidence is at most five
minutes old, a mandatory reason and an idempotency key. The Admin journal commits `DISPATCHING`
before HTTP I/O; the platform journal and owning-module mutation commit together. Ambiguous dispatch
becomes `UNKNOWN` and only command-ID reconciliation may advance it. Create provisions a
`PENDING_ACTIVATION` User plus Personal Organization/OWNER invariant and returns a random activation
token once; only SHA-256 is stored. Activation sets the credential and `ACTIVE`. Suspend atomically
sets `SUSPENDED` and revokes refresh sessions; existing access JWTs are rejected by the durable User
status check. Restore returns the User to `ACTIVE` without restoring old sessions. Deletion remains
preflight-only: Identity, Project, Agent, Inference, Runtime, Tooling and Conversation report owner-
local evidence, and active/UNKNOWN/ownership effects block eligibility. `physicalDeletionEnabled`
is controlled by a default-off server setting and enabled explicitly by the verified release overlay.

### M40-PR5 — Durable user cleanup and release acceptance

- module-owned user cleanup participants and fenced job/steps;
- ownership transfer/Organization deletion integration;
- restart/UNKNOWN reconciliation, backup/restore, performance and security acceptance;
- only then enable physical delete in Trusted Beta.

Implemented in M40-PR5. V1038 persists an Identity-owned Job plus exactly 15 ordered Steps with
PostgreSQL due time, claim token, lease owner, lease expiry, fencing token, attempt budget, safe
error evidence and terminal state. Integration coordinates public owner APIs only. Crashes reclaim
expired claims and replay the current idempotent step; stale workers fail. UNKNOWN Automation,
Model or Tool evidence immediately BLOCKS instead of being deleted or retried.

Deletion requires a SUSPENDED User and a fresh eligible preflight. The platform command atomically
writes `DELETION_PENDING` plus the Job. Non-empty owned Organizations continue to block until the
tenant explicitly transfers OWNER. Sole-member Organizations enter the existing ADR-025 DELETING
cleanup protocol; User cleanup defers until their tombstones are DELETED. Finalization removes
credentials, sessions, activation, profile/activity, Memberships and private Conversation/USER
Memory/Knowledge, unlinks auth-event identity, and pseudonymizes the stable User ID as a DELETED
tombstone. Admin owns no cleanup data and never becomes an Organization member.

### M42-PR1 — Organization commands and User-owned resource detail

Implemented in M42-PR1. Identity creates an Organization only with one existing ACTIVE User as its
unique OWNER, updates bounded name/slug metadata and handles explicit administrator decommissioning
by transitioning ACTIVE to DELETING and enqueuing the existing ADR-025 durable cleanup. The HTTP
request never cascades business rows, and Admin never becomes a member.

Inference supplies a paginated owner-filtered form of the existing redacted Provider projection.
Agent supplies a paginated owner-filtered Agent identity projection with status, current version,
revision and active-key count but no Agent prompt/configuration or key material. Both reads are audited and
use dedicated exact scopes. All Organization commands retain recent MFA, reason, dual idempotent
journals and UNKNOWN command-ID reconciliation. No Admin database or Flyway authority changes.

### M43-PR1 — Recoverable Admin browser Session

Implemented in M43-PR1. MFA verification and refresh issue two same-origin cookies: the existing
HttpOnly Refresh credential and a separate readable CSRF value. Refresh/logout require the CSRF
cookie and `X-Admin-CSRF` header to match the durable Session hash, and successful refresh rotates
both values. React reads only the CSRF cookie at startup, performs one single-flight refresh and
keeps the returned access token in memory. Missing/expired evidence returns to login; network failure
is explicit. Logout expires both cookies. No schema, tenant API or internal browser route changed.

See ADR-036 for threat model and rolling-session compatibility.

### M44-PR1 — Organization Membership and explicit OWNER control

Implemented in M44-PR1. Identity exposes bounded ACTIVE/SUSPENDED Membership pages and owns all
mutations. Add/reactivate accepts only ACTIVE Users and non-OWNER roles. Generic role update and
remove reject OWNER; one explicit transfer atomically demotes the previous OWNER to ADMIN, promotes
an existing ACTIVE member and updates the Organization creator. The unique ACTIVE OWNER index
remains the database backstop.

Admin commands use recent MFA, reason, idempotency, exact scopes, dual journals, audit and UNKNOWN
reconciliation. Existing request authorization and Refresh rotation compare current Membership and
role, so stale authority fails immediately. Admin remains outside the Organization. See ADR-037.

### M45-PR1 — Cleanup and Command operations center

Implemented in M45-PR1. Identity composes bounded User/Organization Cleanup Job and ordered Step
evidence, state counts and BLOCKED code aggregation. Admin pages its own Command journal by state,
operation and target. Reads are audited and exact-scope. UNKNOWN commands retain the existing
command-ID reconciliation; Cleanup evidence has no generic retry or state-edit action. See ADR-038.

### M46-PR1 — Per-User complete resource drill-down

Implemented in M46-PR1. Eight owner modules expose bounded redacted projections for 12 resource and
risk-effect kinds. Integration routes one requested kind and composes a fixed count overview; Admin
uses `system-admin:users:resources:read`, audits the read and stores no platform row. Conversation,
Knowledge, Memory, Automation, Runtime, Tool and Model content/payloads plus all credentials,
endpoints and filesystem paths are excluded. Risk effects are read-only and cannot be retried or
reconciled from this surface. See ADR-039.

### M47-PR1 — Administrator principal lifecycle and recovery

Implemented in M47-PR1. Admin V3 adds forced-password evidence and hashed one-use recovery codes.
Existing administrators can create, suspend/restore and recover another principal or revoke its
Session through recent-MFA, reasoned, idempotent local commands. java-totp generates the TOTP Secret
and eight recovery codes; raw provisioning appears only once. Until the temporary password changes,
Session authorization denies the control plane. Password/credential changes increment credential
version and revoke every old Session. Self and last-active protections fail closed. See ADR-040.

### M48-PR1 — Singleton SystemAdministrator convergence

Implemented in M48-PR1 and supersedes M47 multi-principal lifecycle success paths. Admin V4 adds a
singleton unique slot and refuses migration when multiple rows exist. Bootstrap supplies eight
hashed recovery codes. The singleton may list/revoke its own other Sessions and rotate recovery
codes after recent MFA plus current password. Create/suspend/restore/credential-recovery routes
remain mapped only for compatibility and always fail closed.

Emergency recovery is startup-only: the operator supplies the existing login, exact confirmation,
a unique request ID, new password/TOTP/recovery codes and immediately clears the configuration after
success. Credential version increments, all Sessions are revoked, forced password change is set and
the request hash prevents replay; raw factors never enter audit or a command journal. See ADR-041.

## 18. Architecture acceptance criteria

- Admin principal has no Organization identity and cannot call tenant APIs.
- Normal users/Organization owners cannot authenticate to Admin APIs.
- Admin service has no `spaceagent_platform` DB credential or platform secret-decryption key.
- Platform business mutations still execute in owner modules and are idempotent/audited.
- Dashboard login/activity metrics have explicit definitions and freshness timestamps.
- Credential inventory proves configuration without plaintext or ciphertext disclosure.
- User create preserves default Organization/OWNER invariant.
- User deletion cannot pass blockers or UNKNOWN effects and is restart-safe before hard delete.
- Admin outage cannot stop ordinary platform login/chat/project traffic.
- No frontend code is required by M40-PR1 architecture delivery.
# Backend-only Identity additions (M78-PR1 U01)

All tenant and administrator frontend code remains unchanged. These endpoints extend the current
administration plane, not its UI. Platform Server owns records; Admin Server forwards scoped commands
and journals only safe outcomes in its separate database.

| Admin endpoint | Effect | Private service scope |
| --- | --- | --- |
| `PATCH /admin/v1/users/{id}` | Update `displayName` (1–120 characters); login identity/tenant/roles are not editable here | `system-admin:users:update` |
| `POST /admin/v1/users/{id}/session-revocations` | Revoke all refresh tokens and increment access-token version | `system-admin:users:sessions:revoke` |
| `POST /admin/v1/users/{id}/password-resets` | Issue a one-time 15-minute reset token and revoke existing sessions | `system-admin:users:password:reset` |
| `GET /admin/v1/presence` | Read heartbeat-proven online-user/session counts with coverage | `system-admin:presence:read` |

Mutations require the existing authenticated administrator, MFA within five minutes, a reason and
`Idempotency-Key`. Replay/reconciliation never repeats the side effect or returns reset material.
`passwordResetToken` appears only in the first successful reset response and is not journaled.
Only a SHA-256 hash is persisted by Identity. Secure handoff is the administrator's responsibility;
email delivery is not implemented or claimed. The unauthenticated, rate-limited
`POST /api/v1/auth/password-reset` accepts `{resetToken,password}`; successful consumption changes the
password and invalidates older access/refresh tokens. Reissue, another password change, suspension or
global session revocation invalidates outstanding reset tokens by access-version comparison.

Presence integration (API only): `POST /api/v1/presence/heartbeat` accepts optional
`{clientType:"WEB"|"CLI"|"API"|"UNKNOWN"}`. Identity is taken from verified JWT session/user/tenant
claims, never request IDs. Refresh rotation preserves a login session UUID; older JWTs without a
session claim must reauthenticate before heartbeating. Recommended heartbeat interval is 30 seconds;
leases last at most 90 seconds and never outlive the access token. `POST /api/v1/presence/leave` expires
only the caller's session lease. Counts deduplicate users across sessions and check active user,
membership, organization, authorization version, refresh family and access-token revocation.

`NO_CLIENT_HEARTBEATS` returns nullable counts, not zero. `HEARTBEAT_CLIENTS_ONLY` counts only clients
that use the new interface, not all users or devices. `observedSessionCount` counts retained heartbeat
session records, including expired ones, not online sessions. Existing recent-activity and login
statistics retain their original meaning. No frontend heartbeat integration was performed, so these
APIs alone do not make existing clients observable as online.

Migration V1085 preserves existing refresh tokens with version zero and generated session IDs;
existing access tokens remain compatible only until explicit revocation increments their user version.
No service/container was activated as part of source implementation.
