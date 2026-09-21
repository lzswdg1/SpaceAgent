# System audit remediation

Status: COMPLETE (SOURCE, DETERMINISTIC COVERAGE). Workflow: STRICT.
Authorization: user requested all audit fixes in one continuous execution on 2026-09-15.
The private local audit output is not distributed; public limits are summarized below.

## Scope and real recovery boundaries

1. Identity/Memory/Runtime admission (S01–S03): current tenant + read/write + current active actor including legacy Knowledge; immutable Run configuration does not grant current actor authority. COMPLETE.
2. Artifact storage lifecycle (D01/D02): object-lock interlock and independent publication identity/retention; UNKNOWN never automatically replayed. COMPLETE; real isolated PG mutex/upgrade + local/S3 PASS.
3. Workspace/HTTP/Embedding boundaries (S04/F01–F03/P01/P02): Git internals, detached execution HEAD, bounded UTF8/deadlines, model-specific capabilities and managed empty-source cleanup. COMPLETE.
4. Retrieval/list/React performance and maintenance (P03–P06/M01/M02): bounded summaries, two-batch evidence, incremental Markdown, windowed history, serial polling, focused extraction/readable lifecycles and current docs. COMPLETE; Web147/Admin34/build/browser113 PASS.
5. Resource observations/closure: trusted run-bound cgroup/network samples and apparent Workspace snapshots, typed owner/Admin wire and honest coverage. COMPLETE for source scope; no OS hard disk quota/remote resource or production measurement claim.

Final Maven:884 tests,878 passed,6 optional Tika/Milvus environment-dependent cases skipped; no failure/error.
Additional isolated PG bulk/observation test2/2 PASS after fixture-only addition. Worker27/27, package,
architecture/diff and affected Compose quiet config PASS. No source runtime activation, live paid/Provider/GitHub or user-data mutation.
Exact coverage/limits: docs/operations/SYSTEM-AUDIT-REMEDIATION-2026-09-15.md.

No administrator UI redesign; tenant frontend functional/performance fixes are explicitly authorized by this audit request. Preserve existing UI direction, no auto-scroll, distinct coding/reviewer Agents, logical deletion retaining physical code, administrator-only physical cleanup, environment-managed password-only Admin login, same-origin auth and immutable effect ledgers.

## Verification

Current-tenant/membership/VIEWER/suspended actor negative tests; existing HTTP/Run/Tool recovery fixtures; Artifact PostgreSQL concurrent locking + local/S3 fixture lifecycle; Embedding captured requests/batches; Worker path/size/unit tests; React pure/API/Markdown/polling tests and builds; affected source/SQL and one final full deterministic Maven union. Use temporary deterministic dependencies only when required, never mutate the existing business database or restart the running stack.

Live Providers/GitHub, production runsc/TLS/HA, paid RAG quality and actual production hardware/disk quotas remain external acceptance: no authorization inferred from “once complete.” Do not claim these evidence gaps fixed by unit tests, monitoring limits or byte counters.

## Recovery pointer

Next authorized source work: NONE. Activation and external acceptance require a separate explicit request.
M78 U04 is included as source-level trusted observations; other M78 delivered contracts remain intact.
No new queue milestone number, migrations only when a real persistence invariant needs one; applied migrations immutable.
Preserve existing output and user edits; do not stage output or credentials. Final status must state source vs activation and unresolved external gates.
