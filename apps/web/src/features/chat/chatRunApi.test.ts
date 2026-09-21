import {describe,it,expect,vi} from 'vitest'
import {cancelChatRun,resolveChatRun,latestChatRun,type ChatRunStatus} from './chatRunApi'
import type {AuthenticatedRequest} from './types'

const plannedRun=(state='WAITING_PLAN_APPROVAL')=>({
  agentRunId:'run/1',conversationId:'conversation/1',executionState:state,rootTaskId:'task/1',taskPlanId:'plan/1',
} as ChatRunStatus)
const planPath='/api/v1/chat/conversations/conversation%2F1/tasks/task%2F1/plans/plan%2F1'
const resumePath='/api/v1/chat/runs/run%2F1/resume-plan'

describe('durable Chat controls',()=>{
  it('uses owner-scoped lookup/cancel routes and returns a second wait after approval',async()=>{
    const next={executionState:'WAITING_APPROVAL',pendingApprovalId:'second'}
    const request=vi.fn(async()=>next) as unknown as AuthenticatedRequest
    await latestChatRun(request,'c/1');await cancelChatRun(request,'r/1')
    expect(request).toHaveBeenCalledWith('/api/v1/chat/conversations/c%2F1/run')
    expect(request).toHaveBeenCalledWith('/api/v1/chat/runs/r%2F1/cancel',{method:'POST'})
    const result=await resolveChatRun(request,{agentRunId:'r',approvalId:'first',executionState:'WAITING_APPROVAL'} as ChatRunStatus,'approve')
    expect(result).toBe(next)
    expect(request).toHaveBeenCalledWith('/api/v1/chat/runs/r/resume-approval',expect.objectContaining({method:'POST'}))
  })

  it('reads authority and advances a proposed plan through approve, activate, then resume in strict order',async()=>{
    const completed={executionState:'COMPLETED'}
    const request=vi.fn(async(path:string)=>{
      if(path===planPath)return {status:'PROPOSED'}
      if(path===`${planPath}/approve`)return {status:'APPROVED'}
      if(path===`${planPath}/activate`)return {status:'ACTIVE'}
      if(path===resumePath)return completed
      throw Error(`Unexpected path ${path}`)
    })

    expect(await resolveChatRun(request as unknown as AuthenticatedRequest,plannedRun(),'approve')).toBe(completed)
    expect(request.mock.calls).toEqual([
      [planPath],
      [`${planPath}/approve`,{method:'POST'}],
      [`${planPath}/activate`,{method:'POST'}],
      [resumePath,{method:'POST',body:JSON.stringify({taskPlanId:'plan/1'})}],
    ])
  })

  it('continues idempotently from authoritative approved or active plan state',async()=>{
    const approvedRequest=vi.fn(async(path:string)=>path===planPath?{status:'APPROVED'}:path===`${planPath}/activate`?{status:'ACTIVE'}:{executionState:'COMPLETED'})
    await resolveChatRun(approvedRequest as unknown as AuthenticatedRequest,plannedRun(),'approve')
    expect(approvedRequest.mock.calls).toEqual([
      [planPath],
      [`${planPath}/activate`,{method:'POST'}],
      [resumePath,{method:'POST',body:JSON.stringify({taskPlanId:'plan/1'})}],
    ])

    const activeRequest=vi.fn(async(path:string)=>path===planPath?{status:'ACTIVE'}:{executionState:'COMPLETED'})
    await resolveChatRun(activeRequest as unknown as AuthenticatedRequest,plannedRun(),'approve')
    expect(activeRequest.mock.calls).toEqual([
      [planPath],
      [resumePath,{method:'POST',body:JSON.stringify({taskPlanId:'plan/1'})}],
    ])
  })

  it('never resumes when activation fails or does not confirm ACTIVE',async()=>{
    const failed=vi.fn(async(path:string)=>{
      if(path===planPath)return {status:'PROPOSED'}
      if(path===`${planPath}/approve`)return {status:'APPROVED'}
      if(path===`${planPath}/activate`)throw Error('activation failed')
      return {executionState:'COMPLETED'}
    })
    await expect(resolveChatRun(failed as unknown as AuthenticatedRequest,plannedRun(),'approve')).rejects.toThrow('activation failed')
    expect(failed).not.toHaveBeenCalledWith(resumePath,expect.anything())

    const unconfirmed=vi.fn(async(path:string)=>path===planPath?{status:'APPROVED'}:{status:'APPROVED'})
    await expect(resolveChatRun(unconfirmed as unknown as AuthenticatedRequest,plannedRun(),'approve')).rejects.toThrow('must be ACTIVE')
    expect(unconfirmed).not.toHaveBeenCalledWith(resumePath,expect.anything())
  })

  it('retries rejection from an already cancelled plan without issuing a second plan mutation',async()=>{
    const cancelled={executionState:'CANCELLED'}
    const request=vi.fn(async(path:string)=>path===planPath?{status:'CANCELLED'}:cancelled)

    expect(await resolveChatRun(request as unknown as AuthenticatedRequest,plannedRun(),'reject')).toBe(cancelled)
    expect(request.mock.calls).toEqual([
      [planPath],
      ['/api/v1/chat/runs/run%2F1/cancel',{method:'POST'}],
    ])
  })
})
