# ADR-013: LangGraph.js Multi-Agent Skeleton and Contract

- Status: Accepted
- Date: 2026-08-22
- Scope: M17-PR1

## Context

ADR-007 selected TypeScript and LangGraph.js as the final Multi-Agent reasoning layer.
M16 now provides Java-owned TaskPlan/PlanStep state, so a bounded framework and contract
spike can proceed without making TypeScript authoritative. Existing
`orchestration/v1` is a Java/Python compatibility contract and must remain stable.

## Framework decision

- Use `@langchain/langgraph` Graph API with `@langchain/core` and Zod. Package versions
  are exact and captured in `services/multi-agent-orchestrator/package-lock.json`.
- Use the low-level `StateGraph`/schema API for the first spike. Do not adopt
  `@langchain/langgraph-supervisor` until a later spike demonstrates value without
  leaking framework types into SpaceAgent contracts.
- Use Node's standard HTTP and test primitives for the thin transport/test shell; the
  orchestration primitive itself is LangGraph.js, not a custom graph engine.

## Contract decision

- Add framework-neutral JSON Schemas and golden fixtures under
  `contracts/multi-agent/v1`. Preserve all existing v1 proto/fixtures.
- TypeScript validates with strict Zod schemas. Java validates the same golden fixtures
  with Jackson and explicit required/forbidden field assertions.
- Requests carry Java-owned references, context contributions, allowed capabilities,
  execution cursor, and limits. They never carry API keys or decrypted secrets.
- Responses return exactly one typed command proposal. Java will validate, persist, and
  execute it in M18 or later.

## State and side-effect boundary

- Graph state is ephemeral for one invocation. No LangGraph checkpointer or LangGraph
  server is a recovery source in M17.
- TypeScript has no business database/Redis/ORM, Provider credential, child-process,
  Git, MCP, or Tool execution dependency.
- The deterministic spike may propose PLAN_PROPOSED, DELEGATE_SUBTASK,
  APPROVAL_REQUIRED, or COMPLETED. It performs no external side effect.

## Compatibility and deferred work

- Historical note: Python `services/ai-orchestrator` remained compatibility-only in M17
  and was removed after TypeScript parity in M38-PR2.
- M17 does not claim production Multi-Agent, LLM supervisor behavior, parallel execution,
  durable interrupts, or recovery parity.
- M18 adds Java Task-scoped Runtime/RunEvent/cursor contracts and a feature-flagged Java
  adapter before any shadow or cutover work.
