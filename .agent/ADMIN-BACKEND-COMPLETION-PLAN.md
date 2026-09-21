# M78-PR1 Administrator business backend completion

Status: BACKEND_SOURCE_COMPLETE_WITH_EXPLICIT_COVERAGE. Workflow: STRICT.
2026-09-15 audit remediation supplies U04 run-bound trusted cgroup/network samples and apparent Workspace snapshots,
typed private Admin wire/audited scope and public owner-scoped reads. All missing/partial facts remain explicit;
not full remote/historical resource accounting or OS disk quotas. Proof/limits: SYSTEM-AUDIT-REMEDIATION-2026-09-15.md.
The dated selected-scope/deferred paragraphs below are historical, superseded only for U04 source implementation.
2026-09-14: user explicitly requested U02, U03 and U05 together. U04 is deferred; U05 validates only
the delivered U01–U03 contracts and must not claim U04 resource measurements or all of M78 complete.
2026-09-14: user started the approved M79 RAG work. U01 remains complete; resume U02 after M79.
Authorization: complete the administrator business backend; ALL frontends must remain unchanged.

## Scope and recovery boundaries

| Unit | Boundary | Deliverable | State |
| --- | --- | --- | --- |
| U01 | Identity lifecycle and presence | User profile update, session revocation/password-reset lifecycle, authenticated presence leases and administrator queries | COMPLETE |
| U02 | Durable business operation evidence and resource policies | Append-only attempts/outcomes, redacted current Agent review metadata, existing command capability matrix | COMPLETE |
| U03 | Usage accounting and attribution | All-status MODEL/EMBEDDING/TOOL ledgers, immutable attribution, known subtotals and coverage | COMPLETE |
| U04 | Resource measurements | Trusted run-bound samples/snapshots and explicit coverage; production capacity/OS quotas external | COMPLETE |
| U05 | Selected-scope integration and closure proof | U01–U03 Admin wire/security/upgrade/rollback/idempotency/redaction and full regression; excludes U04 | COMPLETE |

Java business owners retain business data. Admin Server retains its separate identity, MFA, command
and administrator audit database. No direct Admin DB access to business tables, user impersonation,
editable audit/ledger facts, or arbitrary user prompt/code editor. Mutations require existing recent
MFA, reason, idempotency and reconciliation safeguards. Resource CRUD is a capability matrix, not
permission to edit every row. Existing create/suspend/restore/deletion/cleanup/organization flows
are reused and regression-tested; missing supported operations are added through owner APIs.

Presence is based on explicit authenticated client-instance heartbeat leases, not last-seen estimates.
User confirmed ALL frontend changes are forbidden, including background heartbeat injection.
The backend must expose coverage/definition so no current client heartbeat integration is claimed.
Resource metrics must declare unit, time/window, scope, measurement coverage and unknown/unavailable
values. Estimated/apparent storage cannot be mislabeled physical allocated disk, and limits cannot
be mislabeled consumption. No historical CPU/network consumption is fabricated.

Audit records are bounded metadata, without request/response bodies, prompts, secrets, code or host
paths. HTTP attempt/outcome and asynchronous effect outcome are distinct. Missing completion after
a crash remains uncertain rather than silently successful. Immutable evidence has no CRUD endpoints.

## Verification and rollout

Forward migrations only. Focused owner/PostgreSQL/HTTP/Admin/client/worker checks at each real boundary;
full Maven and affected compute suites once at closure. Test expired/revoked/cross-user leases,
command replay/conflict, current-configuration review permissions, partial usage, measurement failure,
audit redaction and data retention. All frontend trees are checked byte-for-byte unchanged.
No paid Provider/GitHub/S3 acceptance, remote push, production deployment or real user deletion/reset.
Local activation is not required by this backend-only request; report source versus runtime clearly.

## Progress

U01 discovery: existing user create/suspend/restore/delete and organization CRUD are present; profile
update, explicit all-session revocation and password reset are missing. Access JWTs have no durable
session/version claim; revoking only refresh tokens is insufficient for immediate access revocation.
Presence/online endpoints are absent. Need a revocation-aware Identity session contract before leases.
U01 implemented: V1085/97 migrations, display-name edit (login identity intentionally immutable),
access-version/session-family claims, global session revocation, one-response-only hashed password-reset
token (15 minutes, replay/epoch invalidation), JWT-derived heartbeat/leave and scoped audited Admin read.
Heartbeat coverage remains explicit; ALL frontend trees unchanged. Private/public command proof and
old-schema upgrade pass. Login-audit FK deadlock avoided with PostgreSQL NO KEY UPDATE; cleanup fixture
queue eligibility now uses the claimant's database clock and isolates other tests' queued jobs.

U01 evidence: 73 focused tests across Identity/HTTP/Admin/PostgreSQL/security/architecture/readiness
passed, zero skips. `git diff --check`, base Compose config and backup-script syntax passed.
Command: `./mvnw -q -pl apps/platform-server,apps/platform-admin-server -am
-Dtest=IdentityPresenceServiceTest,PlatformIdentityHttpTest,PlatformSystemAdministrationPostgresTest,SystemAdminInternalAuthenticationFilterTest,PlatformModuleArchitectureTest,PlatformProductionConfigurationValidatorTest,PlatformReleaseReadinessHealthIndicatorTest,AdminPlatformReadServiceTest,AdminPlatformClientTest,PlatformAdminAuthenticationPostgresTest
-Dsurefire.failIfNoSpecifiedTests=false test`.
Evidence log was ephemeral and is not distributed with the repository. Existing stack NOT activated.
Full closure/release/external acceptance remain unrun. No overall M78 completion claim.

## U02/U03/U05 selected delivery — 2026-09-14

- V1095/107 introduces Governance-owned immutable attempt/outcome tables and owner-ledger attribution columns.
  Integration resolves Runtime scope via a public port; no new cross-owner Mapper/DAO/table reads in Inference/Tooling.
- Matched authenticated mutations record route templates and safe IDs before execution in an independent transaction;
  no body/response/name/prompt/secret/path fields. Missing completion is UNCONFIRMED, HTTP202 is acceptance only.
- Admin receives typed business-audit, resource-capabilities, agent-change-evidence, usage/summary and usage/history
  through exact service JWT scopes. No Admin direct business DB, new user impersonation, generic CRUD or review bypass.
- Usage includes all statuses, including failures and UNKNOWN; known Token/cost/duration subtotals are separated from
  missing facts. Tool costs/tokens remain null. Scope/window, current-observed state and retained-history limits are explicit.
- Contract and operational definitions: docs/operations/ADMIN-BUSINESS-EVIDENCE.md.
- Focused owner/HTTP/PG/Admin set: 74 cases; old Governance allowlist corrected to include exactly its two ADR-085-owned
  evidence tables, then architecture32/32 passed. Actual V1094→V1095 attribution backfill and idempotent replay verified.
- Full `./mvnw -fae test`: 860/860 PASS (Shared25, Platform822, Admin13), zero skipped. Final read-only REPEATABLE READ
  snapshot hardening passed 37/37 affected integration/architecture tests. `./mvnw -DskipTests package`, architecture,
  base Compose config, backup-script syntax and diff checks passed. No business runtime was restarted or deployed.
- Full log: /tmp/spaceagent-m78-final.log; final snapshot log: /tmp/spaceagent-m78-snapshot-final.log (ephemeral logs;
  durable evidence is summarized here). All frontends/output/credentials untouched; no push or external paid calls.

Exact next action: U04 resource measurements and their dedicated validation. U05's COMPLETE refers only to the
user-selected U01–U03 delivery; U04 and overall M78 still require implementation/acceptance. Do not fabricate measurements.
