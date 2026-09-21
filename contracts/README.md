# Cross-language contracts

This directory contains canonical, framework-neutral contracts between the Java
platform-server, the TypeScript Multi-Agent orchestrator, and the Python OCI sandbox worker.

## Language ownership

- Java owns durable business and execution state.
- TypeScript owns interaction and target Multi-Agent graph/reasoning definitions.
- Python owns optional isolated OCI execution only.

The orchestrator and worker are replaceable infrastructure. They must never become
the system of record for Project, Task, Workspace, Conversation, Memory, AgentRun,
RunStep, Checkpoint, ToolExecutionLedger, or Handoff.

## Canonical schema

The canonical Sandbox schema is the Protobuf definition under `contracts/proto/`:

- `execution/v1/sandbox_execution.proto`

The Multi-Agent boundary is independently versioned under `contracts/multi-agent/v1`
with strict JSON Schemas and golden fixtures.

The independent administration boundary is versioned under `contracts/platform-admin/v1`.
Its schemas are redacted projections and command evidence only. A successful User-create command
may return an activation token once; that plaintext is deliberately absent from both durable
command journals and every replay/reconciliation response.
M40-PR5 adds the bounded User cleanup Job/Step projection. It contains only safe lifecycle evidence;
reason text, login identifiers, credentials and private content are excluded.
M48-PR1 constrains the administrator page to at most one singleton principal. Administrator
lifecycle command success is limited to revoking another Session of that same principal; recovery
code rotation returns eight plaintext codes once while Admin V4 stores only their hashes.

The current wire transport is JSON over HTTP, not gRPC. The JSON mapping follows the
standard Protobuf JSON mapping:

- field names use the Protobuf `json_name` (lower camelCase by default);
- enums are serialized as their string names;
- `int64`/timestamps are serialized as strings or numbers as documented per field.

## Sandbox execution contract

A sandbox execution request carries:

- `executionId`;
- `agentRunId`;
- `toolCallId`;
- workspace/task reference;
- command/tool and arguments;
- timeout;
- resource policy.

The response carries exit status, stdout/stderr or artifact references, and execution
metadata. The Java ToolExecutionLedger remains authoritative and wraps sandbox execution;
worker retries must never bypass tool idempotency.

## Golden fixtures

`contracts/fixtures/sandbox` contains shared golden JSON documents used by Java and Python
Sandbox contract tests. `contracts/multi-agent/v1/fixtures` is shared by Java and TypeScript.
