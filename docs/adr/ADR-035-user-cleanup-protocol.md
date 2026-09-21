# ADR-035: Fenced User Cleanup and Tombstone Protocol

- Status: Accepted / implemented by M40-PR5
- Date: 2026-08-26
- Scope: platform-level regular User erasure

## Context

M40-PR4 can suspend a User and report deletion blockers, but deleting `platform_users` in an HTTP
transaction would break Organization creator history, invitations, approvals and immutable evidence.
User data also spans Runtime leases, UNKNOWN Model/Tool effects, private Conversation/Memory/
Knowledge and external-resource owners. The independent Admin service cannot own or directly mutate
any of those rows.

## Decision

Identity owns `platform_user_cleanup_jobs` and ordered Steps. A deletion request requires recent
Admin MFA, exact write scope, mandatory reason, an idempotent command, a SUSPENDED User and an
eligible owner-module preflight. The platform command atomically moves the User to
`DELETION_PENDING` and inserts the Job/Steps.

PostgreSQL owns due time, claim token, worker identity, lease, monotonic fencing token, attempt
budget, retry/BLOCKED state and terminal evidence. A crash repeats only the current idempotent owner
step after lease expiry. Stale worker mutations fail. UNKNOWN Automation/Model/Tool effects block
immediately and are never deleted or blindly retried.

Integration invokes public owner APIs in this order:

1. freeze Identity access and sessions;
2. freeze/purge user Automation;
3. quiesce Runtime and drain old leases;
4. resolve Memberships and sole-member Organizations;
5. purge Artifact and Runtime;
6. purge private Conversation, USER Memory and Knowledge;
7. verify/purge Project, Agent, Inference and Tooling ownership/configuration;
8. cancel pending Governance requests;
9. finalize the Identity tombstone.

The system never chooses a successor owner. A non-empty Organization owned by the User blocks until
an existing active member is explicitly assigned by the tenant workflow. A sole-member Organization
is marked DELETING and handed to the existing ADR-025 Organization cleanup protocol; User cleanup
waits for its DELETED tombstone.

Finalization removes credential, refresh/access revocation, activation, profile, activity,
Membership and private content rows. Auth events lose their direct User link. `platform_users`
remains as a pseudonymized `DELETED` tombstone because Organization/audit records require its stable
ID. Raw reason, idempotency key, login, credentials and content never enter cleanup evidence.

Deletion execution is configuration-gated. It defaults off and is enabled explicitly in the
Trusted Beta release overlay only after deterministic PostgreSQL, restart, backup/restore and
security acceptance pass.

## Consequences

- Admin DB remains command/audit evidence only and cannot restore platform business state.
- User erasure is auditable, resumable and active-active safe without cross-module repository access.
- “Physical delete” means active data/secret erasure plus a minimal tombstone, not destroying
  immutable referential history.
- Ownership/UNKNOWN blockers require explicit reconciliation; retry cannot bypass them.

## Rejected

- direct `DELETE FROM platform_users` or database cascade;
- Admin service access to `spaceagent_platform`;
- automatic successor selection or hidden System-Administrator membership;
- process-local cleanup state, Redis authority or blind retry after ambiguous effects.
