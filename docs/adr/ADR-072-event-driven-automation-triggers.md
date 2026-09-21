# ADR-072: Event-driven Automation Trigger authority

- Status: Accepted
- Date: 2026-09-08
- Milestone: M62-PR1

## Context

Automation currently owns periodic/one-time Schedule and one durable at-most-once Execution per scheduled or
manual occurrence. Webhook, repository, Task-completion and Follow-up events need durable deduplication, replay
protection and dead-letter handling without turning browser timers, MCP servers, Runtime or transport adapters into
a second scheduling/execution authority.

## Decision

- Automation owns a separate versioned `AutomationTrigger` aggregate. It is not an `AutomationSchedule`; changes
  create a new immutable configuration version in one lineage. Lifecycle is DRAFT/ACTIVE/PAUSED/ARCHIVED and
  revision CAS will fence state changes.
- Trigger source types are WEBHOOK, REPOSITORY, TASK_COMPLETION and FOLLOW_UP. Existing SCHEDULED/MANUAL values
  remain occurrence provenance for Schedule compatibility and cannot instantiate the new Trigger aggregate.
- Webhook source stores at most two opaque signing-key references for rotation plus HMAC-SHA256/body/clock bounds.
  It never stores or returns key bytes, signatures or payloads.
- Repository source pins exact Installation, Connection revision, CapabilitySnapshot/hash, provider repository and
  event allowlist. MCP remains Tooling-owned and external metadata cannot create or mutate a Trigger.
- Task-completion pins exact Project/Task and terminal outcome allowlist. Follow-up pins exact Conversation/source
  Task and bounded delay. Project/Conversation/Runtime state remains in its owner module and is validated through
  public APIs before activation or occurrence creation.
- Trigger configuration has a deterministic SHA-256 over tenant/owner/Agent/content/type/source. Public create and
  lifecycle commands contain no delivery payload, signature secret, Runtime ID or client-selected occurrence state.
- U02 adds PostgreSQL trigger/version/subscription/occurrence/delivery/dedup/dead-letter persistence. Integration
  verifies webhook transport in U03; Tooling maps exact repository events in U04; owner-module internal events enter
  through U05; U06 performs Governance/Runtime execution and known-safe retry/UNKNOWN handling.

## Failure and ownership semantics

Integration verifies transport but stores no business truth. Automation creates one occurrence for one stable source
event digest and owns retry/dead-letter state. Runtime may create Task/Run only from an admitted occurrence and must
preserve AgentVersion, Governance, budget, Ledger and Continuation semantics. Duplicate or UNKNOWN delivery never
creates another occurrence or blindly reruns effects.

## Consequences

U01 defined the framework-independent domain/API. U02 added V1063/75 and Automation-owned memory/PostgreSQL
repositories for exact scoped Trigger versions, Subscriptions, digest-deduplicated Occurrences, Delivery attempts
and DeadLetters. The schema stores no raw payload, signature, credential or secret. U03 adds bounded public
Webhook ingress: Integration verifies timestamped HMAC-SHA256 against at most two server-resolved opaque key refs,
then passes hashes only to Automation for lineage-scoped replay. U04 admits Repository events only after exact
Tooling public qualification/Marketplace evidence matches the pinned installation, ACTIVE Connection revision,
CapabilitySnapshot/hash, provider repository and event allowlist; it makes no remote MCP call. Internal event
U05 maps exact Project Task terminal and Conversation/Chat Task due evidence through public owner APIs into stable
hash-only occurrences. U06 gates each occurrence through Governance before one Runtime Continuation creates the
Task/Run path; only pre-dispatch proven-no-effect failures retry, while post-dispatch ambiguity remains UNKNOWN.
Frontend and live external calls remain excluded.

## M65-PR4 dispatch-recovery amendment

Automation now persists one `AutomationDispatchPlan` per occurrence before creating any Conversation,
Run or Continuation. The plan owns deterministic server-managed IDs, exact Delivery/operation hash,
approval reference and monotonic phase. Conversation, Runtime and Continuation public APIs accept an
optional server-requested ID and replay only the same scope, so a crash after Delivery reservation or
between phases can resume without inventing another business object.

The occurrence remains `DISPATCHING` with a PENDING Delivery while a recoverable phase is incomplete;
the existing stale occurrence worker resumes the same plan. A stable-ID or operation-hash conflict is
UNKNOWN and cannot redispatch. After the effect Continuation begins, existing Delivery claim/fencing and
UNKNOWN rules remain unchanged.

An authenticated owner endpoint may resume only an exact BLOCKED occurrence whose latest Delivery is
`AUTOMATION_APPROVAL_REQUIRED`. Governance must return the matching APPROVED action and operation hash;
then the same plan and Delivery return to dispatch. Foreign, stale, rejected, expired, mismatched or
UNKNOWN evidence cannot resume or create another Run.
