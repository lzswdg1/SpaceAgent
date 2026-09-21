# ADR-020: Observability Read Model and Organization Operations UI

- Status: Accepted
- Date: 2026-08-23
- Scope: M24-PR1

AgentRun, ModelCallLedger, Conversation, AgentDefinition, and Organization tables remain
their modules' authoritative state. Observability owns only disposable read-only SQL views
named `platform_observability_*`; its Java persistence adapter is prohibited from querying
source tables directly or importing another module's repository. Views may be rebuilt and
must never be used for Runtime recovery, permission, billing settlement, or workflow state.

Monitoring APIs are scoped exclusively by the active Organization in the JWT and require
an ACTIVE OWNER/ADMIN membership checked through the Identity public API. Session stop
first resolves the tenant-scoped Observability view, then delegates cancellation through
the Runtime public API. Observability never mutates Runtime persistence.

Usage and latency come from durable ModelCallLedger/AgentRun evidence. Cost sums only
non-null `cost_micros` on SUCCEEDED calls. An entirely unpriced/empty set returns `null`,
while an explicitly priced zero returns zero. `hasIncompleteCost=true` signals unpriced
SUCCEEDED calls or UNKNOWN calls without cost evidence; a known subtotal is not a complete
invoice. The platform must not invent USD estimates. Concurrency is an operational approximation, not
a governance quota or recovery invariant.

The existing React Monitoring contracts and components are retained. Monitoring receives
a dedicated Organization-admin route rather than widening the system-admin guard. Vite
targets active platform-server port 9000, and the Nginx frontend is deployable through the
optional Compose `web` profile. Scheduled Tasks and Tracing remain compatibility UI until
their authoritative Governance/Automation/Trace workflows are implemented in later M24
increments.

## Tenant usage contract update — 2026-09-15

- `GET /api/v1/monitoring/overview?range=1d|7d|all` returns the selected window's
  tokens, known cost subtotal and timeseries. `1d` is rolling 24 hours, `7d` rolling
  7 days, `all` has no lower time bound. Defaults stay `7d`; legacy `today` (UTC
  midnight) and `30d` remain supported. Sessions/usage accept the same ranges.
- All-time timeseries use calendar-month buckets, `1d` hourly, `7d` daily. This is
  retained evidence, not a guarantee that physically erased history can be restored.
- `GET /api/v1/monitoring/usage/agents` always returns lifetime usage grouped by
  Agent ID, independent of overview range (equivalent to `/usage?range=all&groupBy=agent`).
  Agent rows sort by totalTokens descending, then Agent ID ascending for stable ties.
  Existing response fields include input/output/cache tokens, call/session counts,
  average latency, known cost and incomplete-cost flag. Only Agents with calls appear.
- The existing 200-row bound and `truncated` flag remain; response totalCostUsd on
  grouped usage is the returned rows' subtotal, not a hidden all-Agent total when truncated.
  Use overview for the organization-wide aggregate. Other groupings retain call-count order.
- These are ModelCallLedger/Runtime statistics, not an all-product invoice: standalone
  Knowledge Embedding calls are not included by these monitoring views. Tokens preserve
  existing recorded-usage semantics; callCount counts all ledger statuses, including failures.
- No change to active-organization OWNER/ADMIN authorization, price configuration,
  budget settlement, historical repricing, or frontend. Unpriced past calls remain unknown.
