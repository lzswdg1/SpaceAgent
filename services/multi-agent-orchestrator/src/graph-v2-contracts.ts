import { z } from "zod";

const hash = z.string().regex(/^sha256:[0-9a-f]{64}$/);
const cursor = z.strictObject({ sequence: z.number().int().min(0), completedCommandIds: z.array(z.string().min(1).max(200)).max(256), pendingCommandId: z.string().min(1).max(200).nullable() });
export const GraphV2RequestSchema = z.strictObject({ contractVersion:z.literal("graph/v2"), requestId:z.string().min(1).max(200), graphSessionId:z.string().min(1), agentRunId:z.string().min(1), tenantId:z.string().min(1), ownerUserId:z.string().min(1), bundleHash:hash, cursor, limits:z.strictObject({ maxDepth:z.number().int().min(1).max(32), maxAgents:z.number().int().min(1).max(32), remainingTokenBudget:z.number().int().min(0) }) });
export const GraphV2ResultSchema = z.strictObject({ contractVersion:z.literal("graph/v2"), requestId:z.string().min(1).max(200), graphSessionId:z.string().min(1), bundleHash:hash, nextCursor:cursor, command:z.strictObject({ commandId:z.string().min(1).max(200), kind:z.enum(["MODEL_REQUESTED","TOOL_REQUESTED","DELEGATE_SUBTASK","HANDOFF_PROPOSED","REVIEW_REQUIRED","WAIT_FOR_APPROVAL","COMPLETED"]), inputHash:hash, payload:z.record(z.string(),z.unknown()).refine(v=>Object.keys(v).length<=32) }), ephemeral:z.literal(true) });
export type GraphV2Request=z.infer<typeof GraphV2RequestSchema>; export type GraphV2Result=z.infer<typeof GraphV2ResultSchema>;
export const parseGraphV2Request=(value:unknown)=>GraphV2RequestSchema.parse(value); export const parseGraphV2Result=(value:unknown)=>GraphV2ResultSchema.parse(value);
