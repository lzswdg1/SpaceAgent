# ADR-052: Pin a redacted OpenTelemetry GenAI subset across runtime boundaries

- Status: Accepted
- Date: 2026-09-06
- Milestone: M52-PR2
- Upstream pin: `open-telemetry/semantic-conventions-genai@94f432d7126f5884d30a2cdde6f4e89908ebb6fd`

## Context

M52-PR1 exports Spring operational traces and database-backed Agent SLO metrics, but ModelCallLedger
does not retain when a streamed Provider produced its first useful chunk. The TypeScript LangGraph
and Python Sandbox services also terminate W3C context at their HTTP boundary, so Tempo cannot show
one causal Java-to-worker trace.

OpenTelemetry moved GenAI conventions to a dedicated repository. As reviewed on 2026-09-06, that
repository has no release and labels Agent/GenAI spans Development. Tracking its moving `main` or
inventing a SpaceAgent-only payload convention would both create unstable contracts.

## Decision

Pin the upstream commit above and implement only this safe subset:

- `invoke_agent` INTERNAL spans for bounded Java Agent execution;
- `chat {model}` CLIENT spans for Provider inference;
- `execute_tool {tool}` INTERNAL spans for Java Tool dispatch and the Sandbox worker;
- `gen_ai.operation.name`, `gen_ai.provider.name`, request model, token usage,
  `gen_ai.request.stream`, `gen_ai.response.time_to_first_chunk`, Agent version and Tool name/type;
- W3C Trace Context propagation through instrumented private HTTP clients and extraction in the
  TypeScript/Python servers.

Prompt, instructions, messages, response, reasoning, Tool definitions/arguments/results, tenant and
resource IDs, secrets and raw exception messages are prohibited even when upstream marks content
attributes opt-in. A constant semantic-convention commit attribute identifies the emitted subset.

The first useful streaming chunk is content, reasoning or a Tool-call delta observed at the real
Provider byte stream. Inference writes `first_chunk_at` using PostgreSQL clock and
`first_chunk_ms` using monotonic time from Provider request dispatch, once under the active
ModelCall claim token/revision. A lost or expired claim cannot write evidence;
non-stream/replayed historical calls remain null rather than estimated.

## Authority and failure semantics

ModelCallLedger owns first-chunk evidence. Existing Runtime/Ledgers and owner-scoped Trace views
remain business truth. OTLP spans, W3C context, metrics and Tempo are disposable projections;
export failure, sampling, duplication or malformed inbound headers never affects business outcomes.
TypeScript and Python SDKs have no business database or durable checkpointer.

## Consequences

V1048 advances the Platform schema and updates Trace/Prometheus TTFC projections. Java, TypeScript
and Python use official OpenTelemetry libraries rather than custom exporters. Updating the GenAI
attribute subset requires reviewing a new upstream commit and amending this ADR; it is never an
implicit dependency upgrade.

## Upstream references

- https://github.com/open-telemetry/semantic-conventions-genai/tree/94f432d7126f5884d30a2cdde6f4e89908ebb6fd
- https://github.com/open-telemetry/semantic-conventions-genai/blob/94f432d7126f5884d30a2cdde6f4e89908ebb6fd/docs/gen-ai/gen-ai-spans.md
- https://github.com/open-telemetry/semantic-conventions-genai/blob/94f432d7126f5884d30a2cdde6f4e89908ebb6fd/docs/gen-ai/gen-ai-agent-spans.md
