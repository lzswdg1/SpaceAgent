# TypeScript Multi-Agent Orchestrator

M17 framework and contract spike using LangGraph.js Graph API and strict Zod schemas.

This service is a compute-only boundary. It owns graph definitions and ephemeral state
for one invocation. Java/PostgreSQL remain authoritative for Organization, Agent,
TaskPlan, Runtime, Checkpoint, Ledgers, Memory, approvals, secrets, and side effects.

```bash
npm install
npm test
npm run build
npm start
```

Endpoints:

- `GET /health`
- `POST /v1/orchestrate` using `contracts/multi-agent/v1`

The deterministic M22 graph remains the default. M28 adds an opt-in boundary-stepped
Provider reasoning mode: TypeScript emits `MODEL_REQUESTED`, Java executes it through its
ModelPool, budget, and ModelCallLedger, then reinvokes the ephemeral graph with a
standardized result. TypeScript still makes no direct Provider, Tool, MCP, Git, database,
or Redis call and never receives Provider credentials.

M54-PR2 makes the existing planner route eligible for both PROJECT and CHAT requests with a pinned
Task. `PLAN_PROPOSED` still contains only bounded strategy and dependency content—never TaskPlan,
Child Task or PlanStep identifiers. Java validates and persists those identifiers and the owner
review lifecycle; ordinary Chat does not automatically invoke or execute the plan until M54-PR3.

M54-PR3A/PR3B now invoke this proposal boundary from eligible Chat and let Java persist, review and
execute the resulting DAG. LangGraph still returns content-only proposals; Java owns the worker
lease, PlanStep/Child Task transitions, Model/Tool ledgers, approval/UNKNOWN continuation and final
synthesis.

M52-PR2 uses the official OpenTelemetry Node SDK to extract W3C Trace Context and emit one redacted
`invoke_workflow spaceagent.multi_agent` child span. OTLP remains default-off:

```bash
PLATFORM_OBSERVABILITY_OTLP_ENABLED=true \
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://127.0.0.1:4318/v1/traces \
npm start
```

The emitted subset is pinned to `open-telemetry/semantic-conventions-genai` commit
`94f432d7126f5884d30a2cdde6f4e89908ebb6fd`. Prompt, response, reasoning, Tool payload and business
resource identifiers are never span attributes. Telemetry owns no graph checkpoint or result.
