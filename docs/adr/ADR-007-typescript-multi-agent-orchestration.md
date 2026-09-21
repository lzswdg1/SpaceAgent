# ADR-007: TypeScript Multi-Agent Orchestration

- Status: Accepted
- Date: 2026-08-22
- Scope: Target product architecture correction

## Context

The V2 repository originally contained an optional Python `services/ai-orchestrator`
adapter and Java-owned Handoff primitives. That was not a complete Multi-Agent product:
there is no durable TaskPlan, PlanStep/child-Task graph, Agent assignment, supervisor,
parallel subagent dispatch, review/merge policy, or Task-scoped execution binding.

The product target now requires:

- Java to remain the authoritative business and Runtime coordinator;
- Multi-Agent reasoning/orchestration to be implemented in TypeScript;
- a mature framework to provide graph, supervisor, subagent, handoff, streaming, and
  human-in-the-loop primitives;
- no second authoritative business database or secret store in the orchestration service;
- provider-neutral execution through the Java-owned ModelPool rather than hard-coded
  provider credentials in the TypeScript process.

## Framework decision

Use **LangGraph.js** as the primary Multi-Agent orchestration framework, with
**LangChain.js** agent/tool/message abstractions and **Zod** schemas.

The official LangGraph JavaScript documentation describes durable graph execution,
streaming, human-in-the-loop, memory, subgraphs, handoffs, parallel work, and supervisor
patterns:

- <https://docs.langchain.com/oss/javascript/langgraph/overview>
- <https://docs.langchain.com/oss/javascript/langgraph/persistence>
- <https://docs.langchain.com/oss/javascript/langgraph/use-subgraphs>
- <https://docs.langchain.com/oss/javascript/langchain/multi-agent/subagents>
- <https://docs.langchain.com/oss/javascript/langchain/multi-agent/handoffs>

The implementation may use `@langchain/langgraph-supervisor` after an implementation
spike verifies compatibility with the selected LangGraph version. Business contracts
must depend on internal interfaces, not directly on that optional convenience package.

### Alternatives

**OpenAI Agents SDK TypeScript** is a mature optional adapter for OpenAI-specific agent
loops, tools, guardrails, handoffs, sessions, and tracing. Official OpenAI documentation
recommends the Agents SDK rather than rebuilding those primitives, and its TypeScript
quickstart demonstrates handoffs. It is not selected as the platform-wide orchestration
core because SpaceAgent must route arbitrary user-supplied OpenAI-compatible providers
through a Java-owned ModelPool:

- <https://platform.openai.com/docs/quickstart>
- <https://developers.openai.com/api/docs/guides/latest-model>

**Mastra** is a credible TypeScript alternative with agents and workflows, including
multi-agent workflow examples. It is not selected for the first implementation because
its workflow, memory, and Runtime surface would overlap more heavily with the existing
Java Runtime authority:

- <https://mastra.ai/en/examples/agents/multi-agent-workflow>

Framework selection must be confirmed by a bounded spike and contract tests before
production rollout. Package versions are pinned in the lockfile during implementation;
this ADR intentionally does not hard-code a future version number.

## Language and state ownership

### Java owns

- Organization/User/Membership and authorization;
- Provider secrets, connection testing, ModelPool, routing, and usage;
- AgentDefinition and immutable AgentVersion;
- Project, Task, TaskPlan, child Tasks, PlanStep state, and acceptance criteria;
- Conversation, Message, ContextSnapshot, and all three memory tiers;
- AgentRun, RunStep, Checkpoint, Recovery, Handoff, approval, and RunEvent;
- ModelCallLedger and ToolExecutionLedger;
- MCP/Skill/Tool permissions and all side-effect execution;
- SourceRepository metadata, Workspace lifecycle, Artifact metadata, and audit.

### TypeScript owns

- LangGraph graph definitions and routing logic;
- supervisor and specialist subagent composition;
- Task/Plan proposals expressed as validated structured output;
- context selection requests and handoff-context shaping;
- reasoning-time delegation, parallelizable subgraph selection, and result synthesis;
- human-in-the-loop interrupt proposals;
- ephemeral graph state during an invocation.

### TypeScript must not own

- authoritative Project/Task/Plan/Conversation/Memory/Run state;
- Provider API keys or decrypted secrets;
- direct business PostgreSQL access;
- direct Git/worktree mutation;
- direct MCP/Tool side effects outside Java ToolExecutionLedger;
- authoritative recovery checkpoints.

## Runtime protocol

The target service is `services/multi-agent-orchestrator`, implemented with Node.js and
TypeScript. Java calls it through versioned internal contracts.

Every request carries references rather than secrets:

```text
agentRunId
organizationId / userId
mode: CHAT | PROJECT
projectId / taskId / taskPlanVersionId / conversationId
agentVersionRefs
contextPackage or contextSnapshotRef
modelPoolRef
allowedSkill/MCP/Tool descriptors
executionCursor and limits
```

The TypeScript service returns typed commands/events:

```text
PLAN_PROPOSED
DELEGATE_SUBTASK
MODEL_REQUESTED
TOOL_REQUESTED
HANDOFF_PROPOSED
APPROVAL_REQUIRED
CHECKPOINT_SUGGESTED
WAITING_FOR_USER
COMPLETED
FAILED
```

Java validates each command, persists the authoritative state transition, executes model
or tool calls through its ledgers, and invokes the graph again with the new durable
cursor. Stable logical call IDs and payload hashes are mandatory.

## Persistence strategy

LangGraph checkpointers are useful framework primitives, but they do not replace the
Java Runtime ledger. The initial production integration is boundary-stepped:

1. Java loads authoritative Run/Task/Plan/Context state.
2. TypeScript executes until the next durable boundary.
3. TypeScript returns a typed command/result.
4. Java persists RunStep/Checkpoint/RunEvent/Ledger state.
5. Java continues or reinvokes TypeScript.

An in-process or temporary LangGraph checkpointer may support one invocation, debugging,
or framework interrupts. Recovery always starts from Java/PostgreSQL. A later custom
checkpointer adapter may project Java checkpoints, but it must not create a second source
of truth.

## Migration from Python — completed

The Python `services/ai-orchestrator` remained a compatibility adapter while the
TypeScript service reached contract parity. M38-PR2 completed this migration and removed
the Python service, Java adapter and old contract. The executed order was:

1. freeze the existing orchestration contract;
2. add shared fixtures for Java/Python/TypeScript compatibility;
3. implement the TypeScript deterministic engine and LangGraph adapter;
4. run shadow comparisons with no side effects;
5. switch a feature flag to TypeScript;
6. remove Python AI-orchestrator routing after recovery and parity gates passed.

The Python Sandbox Worker is not a Multi-Agent orchestrator and remains an OCI-only
infrastructure boundary governed by ADR-033.

## Consequences

- Multi-Agent is now an explicit future product capability, not a claim about the current
  Handoff primitives.
- Java remains the single business and durable Runtime authority.
- TypeScript gains a mature graph/supervisor ecosystem without duplicating secrets,
  persistence, idempotency, or side-effect infrastructure.
- The final target no longer assigns AI orchestration ownership to Python.
- Provider-specific SDKs remain adapters behind internal boundaries and cannot define
  domain contracts.
