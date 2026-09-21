import { END, START, StateGraph, StateSchema, type GraphNode } from "@langchain/langgraph";
import { z } from "zod";

import {
  OrchestrationRequestSchema,
  OrchestrationResponseSchema,
  type OrchestrationRequest,
  type OrchestrationResponse,
} from "./contracts.js";

const RouteSchema = z.enum(["model", "planner", "delegate", "handoff", "review", "approval", "complete"]);

const DecisionStepSchema = z.strictObject({
  stepKey: z.string().min(1).max(80),
  goal: z.string().min(1).max(2_000),
  dependsOnStepKeys: z.array(z.string().min(1).max(80)).max(32),
});

const ReasoningDecisionSchema = z.strictObject({
  route: z.enum(["planner", "delegate", "handoff", "review", "approval", "complete"]),
  rationale: z.string().min(1).max(2_000),
  preferredAgentId: z.string().min(1).nullable(),
  strategySummary: z.string().min(1).max(2_000).nullable(),
  steps: z.array(DecisionStepSchema).max(32),
}).superRefine((decision, context) => {
  const keys = new Set<string>();
  for (const [index, step] of decision.steps.entries()) {
    if (keys.has(step.stepKey)) {
      context.addIssue({ code: "custom", message: "step keys must be unique", path: ["steps", index, "stepKey"] });
    }
    keys.add(step.stepKey);
  }
  const unresolved = new Map(decision.steps.map((step) => [step.stepKey, step.dependsOnStepKeys]));
  for (const [stepKey, dependencies] of unresolved) {
    if (dependencies.some((dependency) => !keys.has(dependency) || dependency === stepKey)) {
      context.addIssue({ code: "custom", message: "step dependencies must reference another known step", path: ["steps"] });
      return;
    }
  }
  const resolved = new Set<string>();
  while (unresolved.size > 0) {
    const ready = [...unresolved].filter(([, dependencies]) =>
      dependencies.every((dependency) => resolved.has(dependency)));
    if (ready.length === 0) {
      context.addIssue({ code: "custom", message: "step dependencies must be acyclic", path: ["steps"] });
      return;
    }
    for (const [stepKey] of ready) {
      unresolved.delete(stepKey);
      resolved.add(stepKey);
    }
  }
});

type Route = z.infer<typeof RouteSchema>;
type ReasoningDecision = z.infer<typeof ReasoningDecisionSchema>;

export class ReasoningDecisionError extends Error {}

const OrchestrationState = new StateSchema({
  request: OrchestrationRequestSchema,
  route: RouteSchema.optional(),
  decision: ReasoningDecisionSchema.optional(),
  response: OrchestrationResponseSchema.optional(),
});

function response(
  request: OrchestrationRequest,
  route: Route,
  command: OrchestrationResponse["command"],
): OrchestrationResponse {
  return OrchestrationResponseSchema.parse({
    contractVersion: "multi-agent/v1",
    requestId: request.requestId,
    agentRunId: request.agentRunId,
    command,
    orchestration: { route, ephemeral: true },
  });
}

function eligibleRoutes(request: OrchestrationRequest): Exclude<Route, "model">[] {
  if (request.cursor.phase === "awaiting_approval") {
    return ["approval"];
  }
  if (request.cursor.phase === "handoff"
      && request.limits.maxDelegations > 0
      && request.limits.remainingTokenBudget > 0
      && request.agentRefs.some((ref) => ref.role !== "supervisor")) {
    return ["handoff", "complete"];
  }
  if (request.cursor.phase === "review"
      && request.taskPlanId !== null
      && request.cursor.planStepId !== null
      && request.context.sources.some((source) => source.type === "ARTIFACT")) {
    return ["review", "complete"];
  }
  if (request.cursor.phase === "execute"
      && request.taskPlanId !== null
      && request.cursor.planStepId !== null
      && request.cursor.childTaskId !== null
      && request.limits.maxDelegations > 0
      && request.limits.remainingTokenBudget > 0
      && request.agentRefs.some((ref) => ref.role !== "supervisor")) {
    return ["delegate", "complete"];
  }
  if (request.cursor.phase === "planning"
      && request.taskId !== null
      && request.taskPlanId === null) {
    return ["planner", "complete"];
  }
  return ["complete"];
}

function parseDecision(request: OrchestrationRequest): ReasoningDecision {
  const result = request.reasoning?.result;
  if (result === null || result === undefined) {
    throw new ReasoningDecisionError("Provider reasoning result is required for round one");
  }
  let value: unknown;
  try {
    value = JSON.parse(result.content);
  } catch {
    throw new ReasoningDecisionError("Provider reasoning result must be strict JSON");
  }
  const parsed = ReasoningDecisionSchema.safeParse(value);
  if (!parsed.success) {
    throw new ReasoningDecisionError("Provider reasoning result does not match the decision schema");
  }
  const decision = parsed.data;
  if (!eligibleRoutes(request).includes(decision.route)) {
    throw new ReasoningDecisionError("Provider reasoning selected an ineligible route");
  }
  if (decision.route === "planner" && (decision.strategySummary === null || decision.steps.length === 0)) {
    throw new ReasoningDecisionError("Planner reasoning requires a strategy and at least one step");
  }
  if ((decision.route === "delegate" || decision.route === "handoff")
      && !request.agentRefs.some((ref) =>
        ref.role !== "supervisor" && ref.agentId === decision.preferredAgentId)) {
    throw new ReasoningDecisionError("Provider reasoning selected an unknown specialist");
  }
  return decision;
}

const supervisor: GraphNode<typeof OrchestrationState> = (state) => {
  const request = state.request;
  if (request.reasoning !== undefined) {
    if (request.reasoning.round === 0) {
      return { route: "model" };
    }
    const decision = parseDecision(request);
    return { route: decision.route, decision };
  }
  return { route: eligibleRoutes(request)[0] };
};

function boundedReasoningInput(request: OrchestrationRequest): string {
  const input = {
    mode: request.mode,
    taskId: request.taskId,
    taskPlanId: request.taskPlanId,
    cursor: request.cursor,
    eligibleRoutes: eligibleRoutes(request),
    agents: request.agentRefs,
    capabilities: request.capabilities,
    contextSources: request.context.sources
      .toSorted((left, right) => right.priority - left.priority)
      .map((source) => ({
        type: source.type,
        sourceId: source.sourceId,
        content: source.content.slice(0, 8_000),
      })),
  };
  return JSON.stringify(input).slice(0, 28_000);
}

const model: GraphNode<typeof OrchestrationState> = (state) => {
  const request = state.request;
  if (request.reasoning === undefined || request.modelPoolRef === null) {
    throw new Error("Provider reasoning requires a ModelPool reference");
  }
  const maxOutputTokens = Math.max(64, Math.min(2048, request.limits.remainingTokenBudget));
  return {
    response: response(request, "model", {
      kind: "MODEL_REQUESTED",
      payload: {
        modelPoolRef: request.modelPoolRef,
        logicalCallId: request.reasoning.logicalCallId,
        messages: [
          {
            role: "system",
            content: "You are the SpaceAgent Supervisor. Return only strict JSON with fields route, rationale, preferredAgentId, strategySummary, and steps. Select only an eligible route and a listed specialist. Never request tools or invent identifiers.",
          },
          { role: "user", content: boundedReasoningInput(request) },
        ],
        parameters: { temperature: 0, maxOutputTokens, responseFormat: "json_object" },
      },
    }),
  };
};

const planner: GraphNode<typeof OrchestrationState> = (state) => {
  const request = state.request;
  const decision = state.decision;
  const goal = request.context.sources.find((source) => source.type === "TASK")?.content
    ?? "Complete the current durable task";
  return {
    response: response(request, "planner", {
      kind: "PLAN_PROPOSED",
      payload: {
        rootTaskId: request.taskId!,
        strategySummary: decision?.strategySummary
          ?? "Create one bounded step from the current durable goal",
        steps: decision?.steps.length ? decision.steps : [
          { stepKey: "execute", goal, dependsOnStepKeys: [] },
        ],
      },
    }),
  };
};

const delegate: GraphNode<typeof OrchestrationState> = (state) => {
  const request = state.request;
  const preferred = request.agentRefs.find((ref) =>
    ref.role !== "supervisor" && (state.decision?.preferredAgentId === null
      || state.decision?.preferredAgentId === undefined
      || ref.agentId === state.decision.preferredAgentId))!;
  return {
    response: response(request, "delegate", {
      kind: "DELEGATE_SUBTASK",
      payload: {
        taskPlanId: request.taskPlanId!,
        planStepId: request.cursor.planStepId!,
        childTaskId: request.cursor.childTaskId!,
        preferredAgentId: preferred.agentId,
      },
    }),
  };
};

const approval: GraphNode<typeof OrchestrationState> = (state) => {
  const request = state.request;
  const scopeId = request.cursor.planStepId ?? request.taskPlanId ?? request.taskId!;
  return {
    response: response(request, "approval", {
      kind: "APPROVAL_REQUIRED",
      payload: {
        scopeType: request.cursor.planStepId === null ? "TASK_PLAN" : "PLAN_STEP",
        scopeId,
        reason: state.decision?.rationale ?? "Java approval is required before continuing",
      },
    }),
  };
};

const handoff: GraphNode<typeof OrchestrationState> = (state) => {
  const request = state.request;
  const target = request.agentRefs.find((ref) =>
    ref.role !== "supervisor" && (state.decision?.preferredAgentId === null
      || state.decision?.preferredAgentId === undefined
      || ref.agentId === state.decision.preferredAgentId))!;
  return {
    response: response(request, "handoff", {
      kind: "HANDOFF_PROPOSED",
      payload: {
        sourceAgentRunId: request.agentRunId,
        targetAgentId: target.agentId,
        summary: state.decision?.rationale
          ?? "Transfer the durable task and evidence to the isolated specialist",
      },
    }),
  };
};

const review: GraphNode<typeof OrchestrationState> = (state) => {
  const request = state.request;
  return {
    response: response(request, "review", {
      kind: "REVIEW_REQUIRED",
      payload: {
        taskPlanId: request.taskPlanId!,
        planStepId: request.cursor.planStepId!,
        artifactIds: request.context.sources
          .filter((source) => source.type === "ARTIFACT")
          .map((source) => source.sourceId),
      },
    }),
  };
};

const complete: GraphNode<typeof OrchestrationState> = (state) => ({
  response: response(state.request, "complete", {
    kind: "COMPLETED",
    payload: { summary: state.decision?.rationale ?? "No additional orchestration step is required" },
  }),
});

export const orchestrationGraph = new StateGraph(OrchestrationState)
  .addNode("supervisor", supervisor)
  .addNode("model", model)
  .addNode("planner", planner)
  .addNode("delegate", delegate)
  .addNode("approval", approval)
  .addNode("handoff", handoff)
  .addNode("review", review)
  .addNode("complete", complete)
  .addEdge(START, "supervisor")
  .addConditionalEdges("supervisor", (state) => state.route ?? "complete", {
    planner: "planner",
    model: "model",
    delegate: "delegate",
    approval: "approval",
    handoff: "handoff",
    review: "review",
    complete: "complete",
  })
  .addEdge("model", END)
  .addEdge("planner", END)
  .addEdge("delegate", END)
  .addEdge("approval", END)
  .addEdge("handoff", END)
  .addEdge("review", END)
  .addEdge("complete", END)
  .compile();

export async function orchestrate(request: OrchestrationRequest): Promise<OrchestrationResponse> {
  const state = await orchestrationGraph.invoke({ request });
  if (state.response === undefined) {
    throw new Error("LangGraph completed without a command proposal");
  }
  return OrchestrationResponseSchema.parse(state.response);
}
