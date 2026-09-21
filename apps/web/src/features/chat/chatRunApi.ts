import type { AuthenticatedRequest,ChatExecution } from './types'
import {resumeChatApproval,reconcileChatTool} from './chatApi'
export type ChatRunStatus = {
  agentRunId:string; conversationId:string; executionState:string; revision:number;
  approvalId:string|null;toolCallId:string|null;toolName:string|null;toolRevision:number|null;
  rootTaskId:string|null;taskPlanId:string|null;partial:boolean;uncertainCalls:number;responseUpdatedAt:string|null;
}
export const chatRunActive=(status:ChatRunStatus|null) => Boolean(status&&!['COMPLETED','FAILED','CANCELLED'].includes(status.executionState))
export const latestChatRun=(request:AuthenticatedRequest,id:string)=>request<ChatRunStatus|null>(`/api/v1/chat/conversations/${encodeURIComponent(id)}/run`)
export const getChatRun=(request:AuthenticatedRequest,id:string)=>request<ChatRunStatus>(`/api/v1/chat/runs/${encodeURIComponent(id)}/status`)
export const cancelChatRun=(request:AuthenticatedRequest,id:string)=>request<ChatRunStatus>(`/api/v1/chat/runs/${encodeURIComponent(id)}/cancel`,{method:'POST'})
export const chatPlanPath=(run:ChatRunStatus)=>`/api/v1/chat/conversations/${encodeURIComponent(run.conversationId)}/tasks/${encodeURIComponent(run.rootTaskId||'')}/plans/${encodeURIComponent(run.taskPlanId||'')}`
type ChatPlanLifecycle = {status:'DRAFT'|'PROPOSED'|'APPROVED'|'ACTIVE'|'COMPLETED'|'CANCELLED'}
export async function resolveChatRun(request:AuthenticatedRequest,run:ChatRunStatus,action:'approve'|'reject'|'reconcile'):Promise<ChatExecution|ChatRunStatus> {
  if(run.executionState==='WAITING_PLAN_APPROVAL'&&run.taskPlanId&&run.rootTaskId){
    const path=chatPlanPath(run)
    let plan=await request<ChatPlanLifecycle>(path)
    if(action==='reject'){
      if(plan.status!=='CANCELLED'){
        if(!['DRAFT','PROPOSED','APPROVED','ACTIVE'].includes(plan.status))throw Error(`Chat TaskPlan cannot be cancelled from ${plan.status}`)
        plan=await request<ChatPlanLifecycle>(`${path}/cancel`,{method:'POST'})
        if(plan.status!=='CANCELLED')throw Error('Chat TaskPlan cancellation was not confirmed')
      }
      return cancelChatRun(request,run.agentRunId)
    }
    if(action!=='approve')throw Error('Chat TaskPlan is not waiting for Tool reconciliation')
    if(plan.status==='PROPOSED')plan=await request<ChatPlanLifecycle>(`${path}/approve`,{method:'POST'})
    if(plan.status==='APPROVED')plan=await request<ChatPlanLifecycle>(`${path}/activate`,{method:'POST'})
    if(plan.status!=='ACTIVE')throw Error(`Chat TaskPlan must be ACTIVE before resume, but was ${plan.status}`)
    return request<ChatExecution>(`/api/v1/chat/runs/${encodeURIComponent(run.agentRunId)}/resume-plan`,{
      method:'POST',body:JSON.stringify({taskPlanId:run.taskPlanId}),
    })
  }
  if(action==='reconcile'&&run.toolCallId&&run.toolRevision!=null){
    return reconcileChatTool(request,run.agentRunId,run.toolCallId,run.toolRevision,'Verify the previous Tool outcome before any continuation')
  }
  if(!run.approvalId)throw Error('No pending approval')
  await request(`/api/v1/governance/approvals/${encodeURIComponent(run.approvalId)}/decision`,{
    method:'POST',body:JSON.stringify({decision:action==='approve'?'APPROVED':'REJECTED',note:'Decision from conversation owner'}),
  })
  return action==='approve'?resumeChatApproval(request,run.agentRunId,run.approvalId):cancelChatRun(request,run.agentRunId)
}
