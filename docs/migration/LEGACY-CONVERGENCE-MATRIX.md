# Legacy Capability Convergence Matrix

- Date: 2026-08-22
- Active backend: `apps/platform-server`
- Read-only archive: a private archive not included in this public snapshot
- Rule: archived `backend/` and old services are not restored into the active source tree.

State meanings are limited to `CONVERGED_REMOVED`, `PARTIAL_MIGRATION`,
`REFERENCE_ONLY`, `ACTIVE_COMPATIBILITY`, and `NOT_MIGRATED`.

| Domain | Legacy Source | Current Replacement | State | Action |
| --- | --- | --- | --- | --- |
| identity | `backend/user`, `identity-service` | `platform.identity` + PostgreSQL identity tables | CONVERGED_REMOVED | Keep current owner/tenant/membership model. |
| agent | `backend/agent`, `agent-service` | canonical `AgentDefinition` + immutable `AgentVersion` | CONVERGED_REMOVED | C1 removed duplicate active identities; retain unknown old Definition rows reference-only. |
| inference | `backend/model`, legacy AI adapters | `platform.inference` + ModelCallLedger | PARTIAL_MIGRATION | Future batch: ModelPool, routing, fallback, provider-specific parity. |
| conversation | `backend/chat`, `chat-service` | `platform.conversation` + Java Chat Runtime | PARTIAL_MIGRATION | Future batch: explicit multi-Agent conversation binding only when authorized. |
| memory | `backend/memory` | `platform.memory` candidates/recall/consolidation | PARTIAL_MIGRATION | Future batch: product policy/UX parity; do not auto-promote chat to durable memory. |
| knowledge | `backend/knowledgebase`, `knowledge-service` | `platform.knowledge` document/chunk/retrieval | CONVERGED_REMOVED | Maintain Java/PostgreSQL ownership and external embedding boundary. |
| runtime | legacy chat/interaction coordinators | `platform.runtime` Run/Step/Checkpoint/Recovery | PARTIAL_MIGRATION | Future batch: worker lease, CAS/fencing, RunEvent, async continuation. |
| tooling | legacy AI tool registry | `platform.tooling` ledger + sandbox gateway | PARTIAL_MIGRATION | Future batch: broaden allowlisted tools; keep UNKNOWN reconciliation semantics. |
| project | archived project references | persisted `platform.project` Project/Membership foundation | PARTIAL_MIGRATION | Current Project identity/API/authorization is active; archived project references were not backfilled. |
| task | archived follow-up/task references | persisted `platform.project` Task intent/lifecycle foundation | PARTIAL_MIGRATION | Current Task API and TASK Memory ancestry are active; assignment and Runtime execution remain future work. |
| workspace | archived file/workspace concepts | `platform.project.Workspace` reference only | NOT_MIGRATED | Future Workspace milestone: Git/worktree source ownership. |
| channel | `backend/channel` Web/Telegram/WeChat Work | HTTP/SSE edge only | NOT_MIGRATED | Future Channel milestone; archived adapters remain tag-only. |
| artifact | archived file/output concepts | `platform.artifact` package boundary | NOT_MIGRATED | Future Artifact milestone: metadata, storage reference, retention. |
| automation | legacy schedule/webhook classes | `platform.automation` package boundary | NOT_MIGRATED | Future Automation milestone: schedule/event model and durable dispatch. |
| governance | legacy rate/usage policy fragments | `platform.governance` package boundary | NOT_MIGRATED | Future Governance milestone: quotas, approvals, audit policy. |
| observability | legacy monitoring/usage endpoints | Actuator/telemetry plus package boundary | PARTIAL_MIGRATION | Future batch: durable product metrics, traces, SLO dashboards. |
| sandbox | legacy in-process tools | retained `sandbox-worker` + Java sandbox gateway | ACTIVE_COMPATIBILITY | Keep optional boundary; do not describe it as a complete security sandbox. |
| multi-agent | archived interaction concepts | Handoff snapshot primitives only | NOT_MIGRATED | Future Multi-Agent milestone after Task/Assignment and durable runtime. |

`REFERENCE_ONLY` applies to retained evidence such as
`platform_legacy_agent_definitions`, migration records, and the Git tag; it does not make
the archived runtime active or supported.
