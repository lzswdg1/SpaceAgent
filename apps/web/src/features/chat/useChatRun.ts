import {useCallback,useEffect,useRef,useState} from 'react'
import type {AuthenticatedRequest,RuntimeEvent} from './types'
import {cancelChatRun,chatPlanPath,chatRunActive,latestChatRun,resolveChatRun,type ChatRunStatus} from './chatRunApi'

export function useChatRun(request:AuthenticatedRequest,conversationId:string|null){
  const [status,setStatus]=useState<ChatRunStatus|null>(null)
  const [error,setError]=useState<string|null>(null)
  const [busy,setBusy]=useState(false)
  const [refreshKey,setRefreshKey]=useState(0)
  const [loadedConversation,setLoadedConversation]=useState<string|null>(null)
  const [plan,setPlan]=useState<{steps:{stepKey:string;expectedOutput:string}[]}|null>(null)
  const current=useRef(status),generation=useRef(0)
  const conversation=useRef(conversationId)
  conversation.current=conversationId
  current.current=status
  const refresh=useCallback(()=>{setError(null);setRefreshKey(value=>value+1)},[])
  useEffect(()=>{setStatus(null);setError(null);setPlan(null)},[conversationId])
  useEffect(()=>{
    const version=++generation.current
    if(!conversationId)return
    let loading=false
    const load=async()=>{
      if(loading)return;loading=true
      try{const result=await latestChatRun(request,conversationId);if(version===generation.current){setStatus(result);setLoadedConversation(conversationId)}}
      catch(reason){if(version===generation.current)setError(reason instanceof Error?reason.message:'Run state unavailable')}
      finally{loading=false}
    }
    void load()
    const timer=setInterval(()=>{if(document.visibilityState==='visible'&&chatRunActive(current.current))void load()},2000)
    return()=>{generation.current++;clearInterval(timer)}
  },[request,conversationId,refreshKey])
  useEffect(()=>{
    setPlan(null)
    if(status?.executionState!=='WAITING_PLAN_APPROVAL'||!status.taskPlanId)return
    let active=true
    request<{steps:{stepKey:string;expectedOutput:string}[]}>(chatPlanPath(status)).then(value=>active&&setPlan(value)).catch(reason=>active&&setError(String(reason)))
    return()=>{active=false}
  },[request,status?.taskPlanId,status?.executionState])
  const onEvent=(event:RuntimeEvent)=>{
    if(!['run_accepted','runtime_started'].includes(event.type))return
    const data=event.data as {agentRunId?:string;conversationId?:string}
    const id=conversation.current
    if(!data?.agentRunId||!id||(data.conversationId&&data.conversationId!==id))return
    if(current.current?.agentRunId!==data.agentRunId){
      const next={agentRunId:data.agentRunId,conversationId:id,executionState:'IN_PROGRESS',revision:0,approvalId:null,
        toolCallId:null,toolName:null,toolRevision:null,rootTaskId:null,taskPlanId:null,partial:false,uncertainCalls:0,responseUpdatedAt:null}
      current.current=next;setStatus(next);setLoadedConversation(id)
      refresh()
    }
  }
  const act=async(action:'approve'|'reject'|'reconcile'|'cancel')=>{
    if(!status||status.conversationId!==conversation.current||busy)return false
    const version=generation.current;setBusy(true);setError(null)
    try{
      if(action==='cancel')await cancelChatRun(request,status.agentRunId)
      else await resolveChatRun(request,status,action)
      if(version===generation.current)refresh()
      return true
    }catch(reason){if(version===generation.current){setError(reason instanceof Error?reason.message:'Run operation failed');setRefreshKey(value=>value+1)}return false}
    finally{setBusy(false)}
  }
  const visible=status?.conversationId===conversationId?status:null
  return {status:visible,error,busy,plan,active:chatRunActive(visible),ready:!conversationId||loadedConversation===conversationId,refresh,onEvent,act,
    historyKey:`${status?.agentRunId}:${status?.revision}:${status?.responseUpdatedAt}`}
}
