import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import test from "node:test";

import { createOrchestrationServer } from "../src/server.js";
import { fixture } from "./fixtures.js";
import {
  GEN_AI_SEMCONV_COMMIT,
  extractedTraceId,
  shutdownTelemetry,
  startTelemetry,
} from "../src/telemetry.js";

test("HTTP adapter serves health, valid orchestration and stable client errors", async (context) => {
  const internalToken = "test-internal-token-0123456789-abcdef";
  const server = createOrchestrationServer(internalToken);
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  context.after(() => server.close());
  const port = (server.address() as AddressInfo).port;
  const base = `http://127.0.0.1:${port}`;

  const health = await fetch(`${base}/health`);
  assert.equal(health.status, 200);
  assert.deepEqual(await health.json(), {
    status: "UP", framework: "LANGGRAPH_JS", durableState: false, sideEffects: false,
  });

  const valid = await fetch(`${base}/v1/orchestrate`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${internalToken}` },
    body: JSON.stringify(await fixture("request-plan.json")),
  });
  assert.equal(valid.status, 200);
  assert.equal((await valid.json() as { command: { kind: string } }).command.kind, "PLAN_PROPOSED");

  const graphV2 = await fetch(`${base}/v1/graph-v2`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${internalToken}` },
    body: JSON.stringify({ contractVersion: "graph/v2", requestId: "graph-request",
      graphSessionId: "graph-session", agentRunId: "agent-run", tenantId: "tenant",
      ownerUserId: "owner", bundleHash: `sha256:${"a".repeat(64)}`,
      cursor: { sequence: 0, completedCommandIds: [], pendingCommandId: null },
      limits: { maxDepth: 2, maxAgents: 2, remainingTokenBudget: 100 } }),
  });
  assert.equal(graphV2.status, 200);
  assert.equal((await graphV2.json() as { contractVersion: string }).contractVersion, "graph/v2");
  assert.equal((await fetch(`${base}/v1/graph-v2`, { method: "POST", body: "{}" })).status, 401);

  const unauthorized = await fetch(`${base}/v1/orchestrate`, { method: "POST", body: "{}" });
  assert.equal(unauthorized.status, 401);

  const malformed = await fetch(`${base}/v1/orchestrate`, {
    method: "POST", headers: { authorization: `Bearer ${internalToken}` }, body: "{",
  });
  assert.equal(malformed.status, 400);
  assert.equal((await malformed.json() as { code: string }).code, "MALFORMED_JSON");

  const invalid = await fetch(`${base}/v1/orchestrate`, {
    method: "POST", headers: { authorization: `Bearer ${internalToken}` },
    body: JSON.stringify({ contractVersion: "multi-agent/v1" }),
  });
  assert.equal(invalid.status, 400);
  assert.equal((await invalid.json() as { code: string }).code, "VALIDATION_ERROR");

  const provider = await fixture("request-provider-reasoning.json") as Record<string, unknown>;
  const invalidReasoning = await fetch(`${base}/v1/orchestrate`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${internalToken}` },
    body: JSON.stringify({
      ...provider,
      reasoning: {
        mode: "PROVIDER",
        round: 1,
        logicalCallId: "multi-agent:supervisor:r7",
        result: {
          content: "not-json",
          inputTokens: 5,
          outputTokens: 2,
          selectedProviderId: "provider-1",
          selectedModelId: "model-1",
          candidateSnapshotHash: null,
        },
      },
    }),
  });
  assert.equal(invalidReasoning.status, 502);
  assert.equal((await invalidReasoning.json() as { code: string }).code,
    "REASONING_RESULT_INVALID");

  const oversized = await fetch(`${base}/v1/orchestrate`, {
    method: "POST", headers: { authorization: `Bearer ${internalToken}` },
    body: "x".repeat(1_048_577),
  });
  assert.equal(oversized.status, 413);
  assert.equal((await oversized.json() as { code: string }).code, "PAYLOAD_TOO_LARGE");
});

test("W3C parent extraction is standard and telemetry exposes no payload", () => {
  const traceId = "0af7651916cd43dd8448eb211c80319c";
  assert.equal(extractedTraceId({
    traceparent: `00-${traceId}-b7ad6b7169203331-01`,
  }), traceId);
  assert.equal(extractedTraceId({ traceparent: "invalid" }), undefined);
  assert.match(GEN_AI_SEMCONV_COMMIT, /^[0-9a-f]{40}$/);
  assert.doesNotMatch(JSON.stringify({ commit: GEN_AI_SEMCONV_COMMIT }),
    /prompt|reasoning|tool.*arguments|tool.*result/i);
});

test("invalid optional OTLP configuration never blocks orchestration startup", async () => {
  await startTelemetry({
    PLATFORM_OBSERVABILITY_OTLP_ENABLED: "true",
    OTEL_EXPORTER_OTLP_TRACES_ENDPOINT: "://invalid",
  } as NodeJS.ProcessEnv);
  await shutdownTelemetry();
});
