import type { AuthenticatedRequest } from '../chat/types'
import type { ProjectPlanExecution,Task,TaskPlan } from './types'

export async function launchCodingConversation(request: AuthenticatedRequest, input: {
  projectId:string;directoryId:string;sourceId:string;conversationId:string;
  agentId:string;reviewerAgentId:string;baseRef:string;goal:string;idempotencyKey?:string;
},tx:(key:string)=>string) {
  if(!input.reviewerAgentId||input.agentId===input.reviewerAgentId)throw Error(tx('distinctReviewerRequired'))
  const {projectId,idempotencyKey,...body}=input
  return request<{root:Task;child:Task;plan:TaskPlan;execution:ProjectPlanExecution}>(
    `/api/v1/projects/${encodeURIComponent(projectId)}/coding-starts`,{
      method:'POST',headers:{'Idempotency-Key':idempotencyKey||crypto.randomUUID()},body:JSON.stringify(body),
    })
}
