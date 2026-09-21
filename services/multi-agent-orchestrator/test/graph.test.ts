import assert from "node:assert/strict";
import test from "node:test";

import { parseRequest, parseResponse } from "../src/contracts.js";
import { orchestrate } from "../src/graph.js";
import { fixture } from "./fixtures.js";

test("real LangGraph routes all deterministic command boundaries", async () => {
  for (const route of ["plan", "delegate", "handoff", "review", "approval", "complete"]) {
    const request = parseRequest(await fixture(`request-${route}.json`));
    const expected = parseResponse(await fixture(`response-${route}.json`));
    assert.deepEqual(await orchestrate(request), expected);
  }
});

test("graph invocation is deterministic and carries no checkpointer state", async () => {
  const request = parseRequest(await fixture("request-plan.json"));
  assert.deepEqual(await orchestrate(request), await orchestrate(request));
});

test("chat planning returns a typed proposal without durable identifiers", async () => {
  const project = parseRequest(await fixture("request-plan.json"));
  const request = parseRequest({ ...project, mode: "CHAT", projectId: null });
  const response = await orchestrate(request);
  assert.equal(response.command.kind, "PLAN_PROPOSED");
  assert.equal(response.orchestration.ephemeral, true);
  if (response.command.kind !== "PLAN_PROPOSED") {
    throw new Error("expected a Chat plan proposal");
  }
  assert.equal(response.command.payload.rootTaskId, request.taskId);
  assert.equal(Object.hasOwn(response.command.payload, "taskPlanId"), false);
  assert.equal(Object.hasOwn(response.command.payload.steps[0]!, "childTaskId"), false);
});

test("supervisor avoids delegation without a specialist or remaining budget", async () => {
  const request = parseRequest(await fixture("request-delegate.json"));
  const response = await orchestrate({
    ...request,
    agentRefs: request.agentRefs.map((ref) => ({ ...ref, role: "supervisor" })),
  });
  assert.equal(response.command.kind, "COMPLETED");
  assert.equal(response.orchestration.route, "complete");

  const exhausted = await orchestrate({
    ...request,
    limits: { ...request.limits, remainingTokenBudget: 0 },
  });
  assert.equal(exhausted.command.kind, "COMPLETED");
});

test("provider reasoning is boundary-stepped and shapes the final command", async () => {
  const base = parseRequest(await fixture("request-plan.json"));
  const first = parseRequest({
    ...base,
    reasoning: {
      mode: "PROVIDER",
      round: 0,
      logicalCallId: "multi-agent:supervisor:r0",
      result: null,
    },
  });

  const modelRequest = await orchestrate(first);
  assert.equal(modelRequest.command.kind, "MODEL_REQUESTED");
  assert.equal(modelRequest.orchestration.route, "model");
  if (modelRequest.command.kind !== "MODEL_REQUESTED") {
    throw new Error("expected a model request");
  }
  assert.equal(modelRequest.command.payload.modelPoolRef, "pool-1");
  assert.equal(modelRequest.command.payload.logicalCallId, "multi-agent:supervisor:r0");
  assert.equal(JSON.stringify(modelRequest).includes("apiKey"), false);

  const second = parseRequest({
    ...first,
    reasoning: {
      ...first.reasoning!,
      round: 1,
      result: {
        content: JSON.stringify({
          route: "planner",
          rationale: "Use two evidence-backed steps",
          preferredAgentId: null,
          strategySummary: "Inspect first, then implement",
          steps: [
            { stepKey: "inspect", goal: "Inspect the durable state", dependsOnStepKeys: [] },
            { stepKey: "implement", goal: "Implement the change", dependsOnStepKeys: ["inspect"] },
          ],
        }),
        inputTokens: 120,
        outputTokens: 80,
        selectedProviderId: "provider-1",
        selectedModelId: "model-1",
        candidateSnapshotHash: "sha256:snapshot",
      },
    },
  });
  const planned = await orchestrate(second);
  assert.equal(planned.command.kind, "PLAN_PROPOSED");
  if (planned.command.kind !== "PLAN_PROPOSED") {
    throw new Error("expected a plan proposal");
  }
  assert.equal(planned.command.payload.strategySummary, "Inspect first, then implement");
  assert.equal(planned.command.payload.steps.length, 2);

  const cyclic = parseRequest({
    ...second,
    reasoning: {
      ...second.reasoning!,
      result: {
        ...second.reasoning!.result!,
        content: JSON.stringify({
          route: "planner",
          rationale: "bad cycle",
          preferredAgentId: null,
          strategySummary: "cyclic",
          steps: [
            { stepKey: "a", goal: "A", dependsOnStepKeys: ["b"] },
            { stepKey: "b", goal: "B", dependsOnStepKeys: ["a"] },
          ],
        }),
      },
    },
  });
  await assert.rejects(() => orchestrate(cyclic), /decision schema/);
});

test("provider reasoning rejects malformed, ineligible, and invented decisions", async () => {
  const base = parseRequest(await fixture("request-delegate.json"));
  const requestWith = (content: string) => parseRequest({
    ...base,
    reasoning: {
      mode: "PROVIDER",
      round: 1,
      logicalCallId: "multi-agent:supervisor:r4",
      result: {
        content,
        inputTokens: 1,
        outputTokens: 1,
        selectedProviderId: "provider-1",
        selectedModelId: "model-1",
        candidateSnapshotHash: null,
      },
    },
  });

  await assert.rejects(() => orchestrate(requestWith("not-json")), /strict JSON/);
  await assert.rejects(() => orchestrate(requestWith(JSON.stringify({
    route: "planner", rationale: "escape scope", preferredAgentId: null,
    strategySummary: "bad", steps: [{ stepKey: "x", goal: "x", dependsOnStepKeys: [] }],
  }))), /ineligible route/);
  await assert.rejects(() => orchestrate(requestWith(JSON.stringify({
    route: "delegate", rationale: "invent agent", preferredAgentId: "agent-other",
    strategySummary: null, steps: [],
  }))), /unknown specialist/);
});
