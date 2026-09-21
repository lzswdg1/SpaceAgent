# ADR-080: Organization Agent Edit Approval Without Versions

- Status: Accepted
- Date: 2026-09-11
- Scope: M75 organization-scoped collaborative Agent editing
- Extends: ADR-079 mutable Agent current configuration and per-Run snapshot

## Context

ADR-079 correctly removed Agent version control. An Agent has one mutable current configuration;
create is immediately usable and save replaces that configuration. The first implementation,
however, permits only the Agent creator to read or save the Agent. That does not support the
required Organization collaboration rule:

- a user freely edits an Agent they created;
- the Organization OWNER freely edits every Agent in that Organization;
- another Organization member may propose a change to an Agent they do not own, but the change
  must not become current until the Organization OWNER approves it;
- a user without an explicitly selected/joined Organization is represented by the registration-
  created personal Organization and OWNER membership, so their own Agent saves remain direct.

Reintroducing Draft/Publish/AgentVersion would violate the product decision. Treating a pending
change as current state would also let an ordinary member affect later Runs before approval.

## Decision

### Authorization matrix

| Actor | Target Agent | Result |
| --- | --- | --- |
| Agent creator | own Agent in active tenant | save current configuration immediately |
| current Organization OWNER | any Agent in active tenant | save current configuration immediately |
| active ADMIN or MEMBER | another user's Agent in active tenant | create/update one pending change request; current configuration is unchanged |
| VIEWER, inactive/non-member, or cross-tenant actor | any Agent | deny without revealing the resource |

Organization creator metadata grants no permanent override after ownership transfer. The current
active OWNER membership is the only Organization-wide direct-edit and approval authority.

### Ownership

- Identity owns current membership status and role.
- Agent owns `AgentConfigurationChangeRequest`, including the complete normalized proposed
  replacement, base Agent revision/configuration hash and terminal application state.
- Governance owns the linked exact-operation `ApprovalRequest` and OWNER decision audit.
- Integration coordinates the Identity decision, Agent proposal and Governance approval through
  public Application APIs in one PostgreSQL transaction.
- Runtime continues to own immutable per-Run configuration snapshots. Pending proposals are never
  visible to Run admission.

### Save behavior

```text
authenticated save
 -> reload active membership and target Agent
 -> creator or Organization OWNER
      -> validate complete replacement -> revision CAS -> current configuration updated
 -> active non-owner writer
      -> validate complete replacement -> persist pending proposal + required OWNER approval
      -> return PENDING_APPROVAL; current configuration unchanged
 -> OWNER decision
      -> re-lock proposal and Agent
      -> reject: terminal audit + erase proposal body
      -> approve: revalidate references/base revision -> update current configuration once
                 -> consume exact approval -> terminal audit + erase proposal body
```

At most one mutable pending proposal exists per requester and Agent. Repeating the same normalized
proposal is idempotent; replacing it does not create history. A direct save or another approved
save makes older-base proposals stale. Terminal, stale and expired requests retain only bounded
hash/actor/time/decision evidence; their proposed configuration body is erased.

### API contract

- Existing creator saves remain backward compatible and return the updated Agent.
- Organization Agent discovery is a separate tenant-member endpoint; the existing personal list
  keeps its current meaning.
- A non-owner save returns an explicit `PENDING_APPROVAL` result and request identifier.
- Requester and Organization OWNER may read the bounded request status. Only the current OWNER may
  approve or reject. HTTP authentication claims are not sufficient; current Identity membership is
  reloaded for every save and decision.

## Failure and concurrency semantics

- Request creation and required Governance approval creation are atomic.
- Approval decision, current-config revision CAS, exact approval consumption and proposal-body
  redaction are atomic.
- Reference validation runs both when proposed and when approved. Revoked ModelPool/Provider,
  Knowledge, Skill, Tool or MCP references fail closed.
- A base revision/hash mismatch produces `STALE` and never overwrites a newer save.
- Lost responses replay the same request or terminal result; approval cannot apply twice.
- Existing Runs never read pending proposals or re-resolve a later current configuration.

## Consequences

- Agent editing stays a single-current-state model with no release/version UI or storage.
- Organization collaboration gains an explicit owner-controlled mutation boundary.
- Pending proposal storage is temporary business input, not Agent history; terminal payload erasure
  prevents it from becoming an alternate version archive.
- Frontend integration is outside M75 unless separately authorized; backend contracts and tests
  must be complete first.
