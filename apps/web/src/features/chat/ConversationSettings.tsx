import {useEffect,useRef,useState,type FormEvent} from 'react'
import {renameConversation,switchConversationAgent} from './chatApi'
import type {AgentSummary,AuthenticatedRequest} from './types'
import './conversation-settings.css'

export type ConversationSettingsUpdate={id:string;title?:string;agentId?:string;updatedAt:string}
type Props={
  request:AuthenticatedRequest;conversation:{id:string;title:string;agentId:string;status:string}|null;
  agents:AgentSummary[];locked:boolean;rootName?:string;tx:(key:string)=>string;
  onUpdated:(value:ConversationSettingsUpdate)=>void;onBusyChange:(busy:boolean)=>void;onDelete:()=>void;onClose?:()=>void;
  loadMoreAgents?:()=>void; hasMoreAgents?:boolean; agentsLoading?:boolean;
  newConversationAgentId?:string; onNewConversationAgentChange?:(agentId:string)=>void;
}

/** Conversation metadata only; Agent definitions remain on the Agent page. Mount keyed by ID. */
export function ConversationSettings({request,conversation,agents,locked,rootName,tx,onUpdated,onBusyChange,onDelete,onClose,loadMoreAgents,hasMoreAgents,agentsLoading,newConversationAgentId='',onNewConversationAgentChange}:Props){
  const [name,setName]=useState(conversation?.title||'')
  const [busy,setBusy]=useState(false),[error,setError]=useState<string|null>(null),[saved,setSaved]=useState(false)
  const alive=useRef(true),inFlight=useRef(false),dirty=useRef(false)
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;onBusyChange(false)}},[onBusyChange])
  useEffect(()=>{if(!dirty.current)setName(conversation?.title||'')},[conversation?.title])
  const disabled=locked||busy||!conversation||conversation.status!=='ACTIVE'
  const options=agents.filter(agent=>!agent.status||agent.status==='ACTIVE')
  const current=agents.find(agent=>agent.id===conversation?.agentId)
  const mutate=async(action:()=>Promise<ConversationSettingsUpdate>)=>{
    if(disabled||inFlight.current)return
    inFlight.current=true;setBusy(true);onBusyChange(true);setError(null);setSaved(false)
    try{
      const result=await action()
      if(result.id!==conversation!.id)throw Error(tx('conversationSettingsFailed'))
      onUpdated(result)
      if(alive.current){if(result.title!==undefined){dirty.current=false;setName(result.title)}setSaved(true)}
    }catch(reason){if(alive.current)setError(reason instanceof Error?reason.message:tx('conversationSettingsFailed'))}
    finally{inFlight.current=false;if(alive.current){setBusy(false);onBusyChange(false)}}
  }
  const saveName=(event:FormEvent)=>{
    event.preventDefault()
    if(!conversation||!name.trim()||name.trim()===conversation.title)return
    void mutate(async()=>{
      const result=await renameConversation(request,conversation.id,name)
      return {id:result.id,title:result.title,updatedAt:result.updatedAt}
    })
  }
  const chooseAgent=(agentId:string)=>{
    if(!conversation||!agentId||agentId===conversation.agentId)return
    void mutate(async()=>{
      const result=await switchConversationAgent(request,conversation.id,agentId)
      return {id:result.conversationId,agentId:result.agentId,updatedAt:result.lastMessageAt}
    })
  }
  return <section className="conversation-settings" aria-label={tx('conversationSettings')} aria-busy={busy}>
    <header><b>{tx('conversationSettings')}</b>{onClose&&<button type="button" className="conversation-settings-close" aria-label={tx('collapseInspector')} onClick={onClose}>×</button>}</header>
    {!conversation?<>
      <p>{tx('selectConversationToConfigure')}</p>
      <label><span>{tx('activeAgent')}</span><select aria-label={tx('conversationAgent')} value={newConversationAgentId} disabled={locked||!options.length} onChange={event=>onNewConversationAgentChange?.(event.target.value)}>
        <option value="" disabled>{tx('noAgent')}</option>
        {options.map(agent=><option key={agent.id} value={agent.id}>{agent.name}</option>)}
      </select></label>
      {hasMoreAgents&&loadMoreAgents&&<button type="button" disabled={locked||agentsLoading} onClick={loadMoreAgents}>{tx('loadMoreAgents')}</button>}
      <p className="conversation-settings-hint">{tx('conversationAgentHint')}</p>
      <a href="/app/agents" target="_blank" rel="noopener noreferrer">{tx('manageAgentDefinition')} ↗</a>
    </>:<>
      <form onSubmit={saveName}>
        <label><span>{tx('conversationName')}</span><input name="conversationName" value={name} maxLength={200} required disabled={disabled}
          onChange={event=>{setName(event.target.value);dirty.current=true;setSaved(false)}}/></label>
        <button type="submit" disabled={disabled||!name.trim()||name.trim()===conversation.title}>{tx('saveConversationName')}</button>
      </form>
      <label><span>{tx('activeAgent')}</span><select aria-label={tx('conversationAgent')} value={conversation.agentId} disabled={disabled||!options.length} onChange={event=>chooseAgent(event.target.value)}>
        {!options.some(agent=>agent.id===conversation.agentId)&&<option value={conversation.agentId} disabled>{current?.name||tx('noAgent')}</option>}
        {options.map(agent=><option key={agent.id} value={agent.id}>{agent.name}</option>)}
      </select></label>
      {hasMoreAgents&&loadMoreAgents&&<button type="button" disabled={disabled||agentsLoading} onClick={loadMoreAgents}>{tx('loadMoreAgents')}</button>}
      <p className="conversation-settings-hint">{tx('conversationAgentHint')}</p>
      <a href="/app/agents" target="_blank" rel="noopener noreferrer">{tx('manageAgentDefinition')} ↗</a>
      {rootName&&<div className="conversation-root-summary"><span>{tx('belongsToRoot')}</span><b>{rootName}</b></div>}
      {locked&&<p>{tx('conversationSettingsLocked')}</p>}
      {error&&<p role="alert">{error}</p>}
      {(busy||saved)&&<p role="status">{tx(busy?'saving':'saved')}</p>}
      <footer><button type="button" className="danger" disabled={disabled} onClick={onDelete}>{tx('deleteConversationAction')}</button></footer>
    </>}
  </section>
}
