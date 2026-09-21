# M81-PR1 — resource-budgeted optional deployment

Workflow STRICT. Status COMPLETE. No feature deletion, frontend work, migration,
paid tests or current-stack activation. One rollout: default-compatible runtime limits and separate
optional low-resource deployment/guard/verification.

## Invariants

All business/permission/recovery owners remain unchanged. Existing normal profiles, Milvus/pgvector,
Tika, independent Knowledge Worker, telemetry and orchestration remain present. Low-resource selects
pgvector, Java monolith and separate Admin plane, static clients and one OCI executor. Heavy services
are not selected, not deleted. Backend pin still refuses implicit Milvus→pgvector switch. No auth,
audit, independent reviewer or UNKNOWN protection is disabled.

Existing SSE and child CPU/memory/PID/tmpfs limits become options with unchanged defaults. Low manifest
sets process budgets, JVM heaps/pools, log rotation and slower polling. Child budget is separate from
worker budget. No API key is read; model execution remains external HTTP.

## Acceptance / rollback

Merged Compose is the budget source. A stdlib guard checks selected services, child/host reserve,
heaps/pools, disabled modes and unsafe overrides. Wrapper clears inherited profiles, uses prebuilt
explicit images, defaults to read-only config; no build/down/prune/deletion. Runtime needs explicit
command/host checks. Focused Java/Python/benign OCI/config tests plus affected closure once; no paid
APIs or existing-stack rebuild. A constrained smoke may prove boot, not actual2CPU/4GiB capacity/HA.

Rollback removes low overlay and explicitly selects retained normal profiles/resources. Never delete
volumes/backend binding or automatically retry pending UNKNOWN effects. Implementation complete:
full Maven904/904 zero skips, Worker29/29, merged guard5/5, package/architecture/diff and true constrained
OCI PASS. Current-Jar boot with1152MiB/1CPU/heap640+PG512/.5 passed readiness/registration/pgvector base;
observed cgroup549662720bytes, not peak/whole-stack capacity. No current-stack activation/image rebuild.
Next source action NONE. Operations/limits: LOW-RESOURCE-DEPLOYMENT.md and dated acceptance report.
