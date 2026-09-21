# ADR-028: Boundary-Stepped Provider-Backed TypeScript Supervisor

- Status: Accepted
- Date: 2026-08-23
- Scope: M28-PR1

## Context

M22 proves the TypeScript + LangGraph.js Supervisor/Subagent/Handoff/Reviewer authority
loop, but its decisions are deterministic. M27 now provides Java-owned ModelPool routing,
known-safe fallback, immutable pricing, Organization budget reservations and a fenced
ModelCallLedger. Sending Provider credentials to TypeScript or adding a second durable
LangGraph store would violate the established ownership model.

## Decision

Provider reasoning uses the existing boundary-stepped protocol. TypeScript proposes a
single typed `MODEL_REQUESTED` command. Java validates it, resolves the authorized
ModelPool, executes inference through the durable ledger and budget boundary, and invokes
the ephemeral LangGraph graph again with a standardized result. TypeScript validates that
result as a strict decision and emits the final typed orchestration command; Java validates
and persists that command exactly as it does for deterministic M22 output.

The deterministic graph remains the default and rollback mode. TypeScript owns no Provider
SDK, credential, database, business state, tool side effect or authoritative checkpoint.
UNKNOWN model outcomes remain blocked until the Java Inference owner reconciles them.

## Consequences

- Provider-backed reasoning can use any active Java ModelPool without exposing secrets.
- Model calls inherit M27 routing, fallback, pricing, quota, replay and UNKNOWN semantics.
- A process restart can repeat the boundary with the same logical call identity and replay a
  committed result without repeating the Provider call.
- This increment does not implement automatic merge, Chat Root Task creation, webhook
  Automation, effect-specific reconciliation or default production cutover.
