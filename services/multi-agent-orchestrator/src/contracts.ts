import { z } from "zod";

const NullableId = z.string().min(1).nullable();

export const ReasoningResultSchema = z.strictObject({
  content: z.string().min(1).max(32_000),
  inputTokens: z.number().int().min(0),
  outputTokens: z.number().int().min(0),
  selectedProviderId: z.string().min(1),
  selectedModelId: z.string().min(1),
  candidateSnapshotHash: z.string().min(1).nullable(),
});

export const ProviderReasoningSchema = z.strictObject({
  mode: z.literal("PROVIDER"),
  round: z.union([z.literal(0), z.literal(1)]),
  logicalCallId: z.string().min(1).max(200),
  result: ReasoningResultSchema.nullable(),
}).superRefine((value, context) => {
  if ((value.round === 0) !== (value.result === null)) {
    context.addIssue({
      code: "custom",
      message: "reasoning result must be absent only for round zero",
      path: ["result"],
    });
  }
});

export const OrchestrationRequestSchema = z.strictObject({
  contractVersion: z.literal("multi-agent/v1"),
  requestId: z.string().min(1),
  agentRunId: z.string().min(1),
  organizationId: z.string().min(1),
  userId: z.string().min(1),
  mode: z.enum(["CHAT", "PROJECT"]),
  projectId: NullableId,
  taskId: NullableId,
  taskPlanId: NullableId,
  conversationId: z.string().min(1),
  agentRefs: z.array(z.strictObject({
    agentId: z.string().min(1),
    runConfigurationSnapshotId: NullableId,
    role: z.string().min(1).max(80),
  })).max(32),
  modelPoolRef: NullableId,
  context: z.strictObject({
    contextPackageId: z.string().min(1),
    tokenBudget: z.number().int().positive(),
    sources: z.array(z.strictObject({
      type: z.string().min(1),
      sourceId: z.string().min(1),
      content: z.string().max(32_000),
      priority: z.number().int(),
    })).max(256),
  }),
  capabilities: z.strictObject({
    allowedToolNames: z.array(z.string().min(1)),
    allowedSkillIds: z.array(z.string().min(1)),
    allowedMcpServerIds: z.array(z.string().min(1)),
  }),
  cursor: z.strictObject({
    phase: z.enum(["planning", "execute", "handoff", "review", "awaiting_approval", "complete"]),
    checkpointId: NullableId,
    planStepId: NullableId,
    childTaskId: NullableId,
  }),
  limits: z.strictObject({
    maxDelegations: z.number().int().min(0).max(100),
    maxParallelAgents: z.number().int().min(1).max(32),
    maxDepth: z.number().int().min(1).max(32),
    remainingTokenBudget: z.number().int().min(0),
  }),
  reasoning: ProviderReasoningSchema.optional(),
});

const PlanProposedCommandSchema = z.strictObject({
  kind: z.literal("PLAN_PROPOSED"),
  payload: z.strictObject({
    rootTaskId: z.string().min(1),
    strategySummary: z.string().min(1),
    steps: z.array(z.strictObject({
      stepKey: z.string().min(1),
      goal: z.string().min(1),
      dependsOnStepKeys: z.array(z.string()),
    })).min(1),
  }),
});

const DelegateSubtaskCommandSchema = z.strictObject({
  kind: z.literal("DELEGATE_SUBTASK"),
  payload: z.strictObject({
    taskPlanId: z.string().min(1),
    planStepId: z.string().min(1),
    childTaskId: z.string().min(1),
    preferredAgentId: NullableId,
  }),
});

const ApprovalRequiredCommandSchema = z.strictObject({
  kind: z.literal("APPROVAL_REQUIRED"),
  payload: z.strictObject({
    scopeType: z.enum(["TASK_PLAN", "PLAN_STEP"]),
    scopeId: z.string().min(1),
    reason: z.string().min(1),
  }),
});

const CompletedCommandSchema = z.strictObject({
  kind: z.literal("COMPLETED"),
  payload: z.strictObject({ summary: z.string().min(1) }),
});

const HandoffProposedCommandSchema = z.strictObject({
  kind: z.literal("HANDOFF_PROPOSED"),
  payload: z.strictObject({
    sourceAgentRunId: z.string().min(1), targetAgentId: z.string().min(1),
    summary: z.string().min(1),
  }),
});

const ReviewRequiredCommandSchema = z.strictObject({
  kind: z.literal("REVIEW_REQUIRED"),
  payload: z.strictObject({
    taskPlanId: z.string().min(1), planStepId: z.string().min(1),
    artifactIds: z.array(z.string().min(1)).min(1),
  }),
});

const ModelRequestedCommandSchema = z.strictObject({
  kind: z.literal("MODEL_REQUESTED"),
  payload: z.strictObject({
    modelPoolRef: z.string().min(1),
    logicalCallId: z.string().min(1).max(200),
    messages: z.array(z.strictObject({
      role: z.enum(["system", "user"]),
      content: z.string().min(1).max(32_000),
    })).min(1).max(4),
    parameters: z.strictObject({
      temperature: z.number().min(0).max(1),
      maxOutputTokens: z.number().int().min(64).max(4096),
      responseFormat: z.literal("json_object"),
    }),
  }),
});

export const OrchestrationCommandSchema = z.discriminatedUnion("kind", [
  PlanProposedCommandSchema,
  DelegateSubtaskCommandSchema,
  ApprovalRequiredCommandSchema,
  CompletedCommandSchema,
  HandoffProposedCommandSchema,
  ReviewRequiredCommandSchema,
  ModelRequestedCommandSchema,
]);

export const OrchestrationResponseSchema = z.strictObject({
  contractVersion: z.literal("multi-agent/v1"),
  requestId: z.string().min(1),
  agentRunId: z.string().min(1),
  command: OrchestrationCommandSchema,
  orchestration: z.strictObject({
    route: z.enum(["model", "planner", "delegate", "handoff", "review", "approval", "complete"]),
    ephemeral: z.literal(true),
  }),
});

export type OrchestrationRequest = z.infer<typeof OrchestrationRequestSchema>;
export type OrchestrationCommand = z.infer<typeof OrchestrationCommandSchema>;
export type OrchestrationResponse = z.infer<typeof OrchestrationResponseSchema>;

export function parseRequest(value: unknown): OrchestrationRequest {
  return OrchestrationRequestSchema.parse(value);
}

export function parseResponse(value: unknown): OrchestrationResponse {
  return OrchestrationResponseSchema.parse(value);
}
