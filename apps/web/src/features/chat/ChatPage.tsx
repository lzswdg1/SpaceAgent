import { ConversationMessage } from './ConversationMessage'
import { usePagedOptions } from '../../lib/usePagedOptions'
import { WorkspaceNavigation } from '../../app/WorkspaceNavigation'
import { useCallback, useEffect, useMemo, useRef, useState, type ChangeEvent, type FormEvent, type KeyboardEvent } from 'react'
import type { Language } from '../../copy'
import { createConversation, deleteConversation, listAgents, listConversations, listMessages, streamMessage } from './chatApi'
import type { AgentSummary, AuthenticatedRequest, ChatDone, ConversationSummary, Message, OpenStream, RuntimeEvent } from './types'
import { ConversationViewport } from './ConversationViewport'
import './chat.css'
import { useMessageHistory } from './useMessageHistory'
import { useChatRun } from './useChatRun'
import { ChatRunPanel } from './ChatRunPanel'
import { ApiError } from '../../lib/api'
import { ConversationSettings, type ConversationSettingsUpdate } from '../chat/ConversationSettings'
import { shouldSubmitComposer } from '../../lib/composerKeyboard'
import { useChatStreamOperation } from './useChatStreamOperation'
import { includeLinkedConversation, loadLinkedConversation } from './conversationLink'
import { appendBoundedRuntimeEvent, createStreamingMessageBatcher } from './streamingMessages'
import { availableConversationAgents, selectNewConversationAgent } from './conversationAgent'

type Props = {
  language: Language
  request: AuthenticatedRequest
  openStream: OpenStream
  navigationCollapsed: boolean
  onToggleNavigation: () => void
  tx: (key: string) => string
}

type UiMessage = Message & {
  id: string
  streaming?: boolean
  reasoning?: string
  reasoningOpen?: boolean
  reasoningStartedAt?: number
  reasoningElapsedMs?: number
}

const now = () => new Date().toISOString()
const resizeComposer = (element: HTMLTextAreaElement | null) => {
  if (!element) return
  element.style.height = '38px'
  element.style.height = `${Math.min(76, Math.max(38, element.scrollHeight))}px`
}

export function ChatPage({ language, request, openStream, navigationCollapsed, onToggleNavigation, tx }: Props) {
  const startBlank = useRef(new URLSearchParams(window.location.search).get('new') === '1')
  const [agents, setAgents] = useState<AgentSummary[]>([])
  const [newConversationAgentId, setNewConversationAgentId] = useState('')
  const agentPages = usePagedOptions((offset, signal) => listAgents(request, offset, signal),
    page => setAgents(rows => [...rows, ...page.filter(agent => !rows.some(row => row.id === agent.id))]),
    reason => setError(reason instanceof Error ? reason.message : tx('chatLoadFailed')))
  const [conversations, setConversations] = useState<ConversationSummary[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(() => new URLSearchParams(window.location.search).get('conversation'))
  const requestedConversation = useRef(startBlank.current ? null : selectedId)
  const linkedConversation = useRef<ConversationSummary | null>(null)
  const [linkError, setLinkError] = useState(false)
  const streamOperation = useChatStreamOperation(request)
  const [messages, setMessages] = useState<UiMessage[]>([])
  const [conversationPage,setConversationPage]=useState(1)
  const [conversationTotal,setConversationTotal]=useState(0)
  const [loadingMore,setLoadingMore]=useState(false)
  const listGeneration=useRef(0)
  const [draft, setDraft] = useState('')
  const [loading, setLoading] = useState(true)
  const [sending, setSending] = useState(false)
  const [settingsBusy, setSettingsBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [runtimeEvents, setRuntimeEvents] = useState<RuntimeEvent[]>([])
  const [runtimeEventCount, setRuntimeEventCount] = useState(0)
  const [done, setDone] = useState<ChatDone | null>(null)
  const [deleteConfirm, setDeleteConfirm] = useState(false)
  const [inspectorCollapsed, setInspectorCollapsed] = useState(() => {
    const stored = window.localStorage.getItem('spaceagent-chat-inspector-collapsed')
    return stored === null ? true : stored === 'true'
  })
  const controller = useRef<AbortController | null>(null)
  const composerRef = useRef<HTMLTextAreaElement>(null)
  const messageLogRef = useRef<HTMLDivElement>(null)
  const selected = conversations.find((conversation) => conversation.id === selectedId) ?? null
  const availableAgents = useMemo(() => availableConversationAgents(agents), [agents])
  const selectedAgent = selected
    ? agents.find((agent) => agent.id === selected.agentId) ?? null
    : selectNewConversationAgent(agents, newConversationAgentId)
  const composerEnabled = !settingsBusy && !loading && !linkError && (selected
    ? Boolean(selectedAgent && (!selectedAgent.status || selectedAgent.status === 'ACTIVE'))
    : availableAgents.length > 0)

  const loadIndex = useCallback(async () => {
    const generation=++listGeneration.current
    setLoading(true)
    setError(null)
    try {
      const [agentRows, conversationPage] = await Promise.all([
        listAgents(request),
        listConversations(request,1,'CHAT'),
      ])
      if(generation!==listGeneration.current)return
      setAgents(agentRows)
      agentPages.reset(agentRows.length)
      setConversationPage(1);setConversationTotal(conversationPage.total)
      setConversations(conversationPage.items)
      const target = requestedConversation.current
      let linked = linkedConversation.current
      if (target || (linked && !conversationPage.items.some(row=>row.id===linked!.id))) {
        try {
          linked = await loadLinkedConversation(request, target || linked!.id, 'CHAT', linked?.title || tx('linkedConversationTitle'))
        } catch (reason) {
          if (generation === listGeneration.current) { setSelectedId(null); setLinkError(true) }
          throw reason
        }
      }
      if (generation !== listGeneration.current) return
      linkedConversation.current = linked
      const rows = includeLinkedConversation(conversationPage.items, linked, row => row.id)
      setConversations(rows)
      setLinkError(false)
      if (streamOperation.isActive()) return
      if (startBlank.current) { startBlank.current = false; setSelectedId(null) }
      else if (target) { requestedConversation.current = null; setSelectedId(target) }
      else setSelectedId((current) => current && rows.some(({ id }) => id === current)
        ? current : rows[0]?.id ?? null)
    } catch (reason) {
      if (generation === listGeneration.current) setError(reason instanceof Error ? reason.message : tx('chatLoadFailed'))
    } finally {
      if (generation === listGeneration.current) setLoading(false)
    }
  }, [request, tx])

  useEffect(() => { void loadIndex() }, [loadIndex])

  const runControl=useChatRun(request,selectedId)
  const history=useMessageHistory(request,selectedId,sending,setMessages,reason=>setError(reason instanceof Error?reason.message:tx('messageLoadFailed')),runControl.historyKey)
  const stop=async()=>{try { await streamOperation.stop() } catch(reason) { setError(reason instanceof Error?reason.message:tx('streamFailed')) } finally {runControl.refresh()} }
  useEffect(()=>{if(runControl.status?.executionState==='COMPLETED'&&error===tx('streamDisconnectedRecovering'))setError(null)},[runControl.status?.executionState,error,tx])
  const loadOlderMessages=async()=>{
    const log=messageLogRef.current, height=log?.scrollHeight??0, top=log?.scrollTop??0
    if(await history.loadOlder())requestAnimationFrame(()=>{if(log)log.scrollTop=top+log.scrollHeight-height})
  }
  const loadMoreConversations=async()=>{
    if(loadingMore||loading)return
    const generation=listGeneration.current;setLoadingMore(true)
    try{const page=await listConversations(request,conversationPage+1,'CHAT')
      if(generation===listGeneration.current){setConversations(rows=>[...rows,...page.items.filter(item=>!rows.some(row=>row.id===item.id))]);setConversationPage(conversationPage+1);setConversationTotal(page.total)}
    }catch(reason){if(generation===listGeneration.current)setError(reason instanceof Error?reason.message:tx('chatLoadFailed'))}
    finally{setLoadingMore(false)}
  }

  useEffect(() => () => controller.current?.abort(), [])
  useEffect(() => resizeComposer(composerRef.current), [draft])

  const toggleInspector = () => setInspectorCollapsed((current) => {
    const next = !current
    window.localStorage.setItem('spaceagent-chat-inspector-collapsed', String(next))
    return next
  })

  const updateDraft = (event: ChangeEvent<HTMLTextAreaElement>) => {
    setDraft(event.currentTarget.value)
    resizeComposer(event.currentTarget)
  }

  const handleComposerKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (!shouldSubmitComposer(event)) return
    event.preventDefault()
    event.currentTarget.form?.requestSubmit()
  }

  const newConversation = async (forSend = false) => {
    if (streamOperation.isActive() && !forSend) return null
    listGeneration.current++
    setLoading(false)
    requestedConversation.current = null
    setLinkError(false)
    if (!selectedAgent) {
      setError(tx('agentRequired'))
      return null
    }
    setError(null)
    try {
      const created = await createConversation(request, selectedAgent.id, tx('newConversation'))
      const summary: ConversationSummary = {
        id: created.conversationId,
        agentId: created.agentId,
        title: tx('newConversation'),
        status: created.status,
        createdAt: created.startedAt,
        updatedAt: created.lastMessageAt,
      }
      setConversations((rows) => [summary, ...rows])
      setSelectedId(created.conversationId)
      setMessages([])
      return summary
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : tx('conversationCreateFailed'))
      return null
    }
  }

  const send = async (event: FormEvent) => {
    event.preventDefault()
    const content = draft.trim()
    if (!content || sending || settingsBusy || loading || linkError || runControl.active || !runControl.ready) return
    const operation = streamOperation.begin()
    if (!operation) return
    setSending(true)
    let conversation = selected
    if (!conversation) conversation = await newConversation(true)
    if (!conversation) { streamOperation.finish(operation); setSending(false); return }
    const agent = agents.find(({ id }) => id === conversation.agentId)
    if (!agent || agent.status === 'ARCHIVED') {
      setError(tx('agentRequired'))
      streamOperation.finish(operation); setSending(false)
      return
    }
    const userMessage: UiMessage = { id: `user-${Date.now()}`, role: 'USER', content, createdAt: now() }
    const assistantId = `assistant-${Date.now()}`
    setMessages((rows) => [...rows, userMessage, { id: assistantId, role: 'ASSISTANT', content: '', createdAt: now(), streaming: true }])
    setDraft('')
    setSending(true)
    setError(null)
    setRuntimeEvents([])
    setRuntimeEventCount(0)
    setDone(null)
    operation.conversationId = conversation.id
    const abortController = operation.controller
    controller.current = abortController
    const deltas = createStreamingMessageBatcher(setMessages, assistantId)
    try {
      await streamMessage(openStream, {
        conversationId: conversation.id,
        agentId: agent.id,
        message: content,
        modelId: agent.modelId,
      }, {
        onReasoningDelta: deltas.reasoning,
        onDelta: deltas.content,
        onRuntimeEvent: (runtimeEvent) => {streamOperation.onEvent(operation,runtimeEvent);runControl.onEvent(runtimeEvent);setRuntimeEventCount((count) => count + 1);setRuntimeEvents((rows) => appendBoundedRuntimeEvent(rows, runtimeEvent))},
        onSuspended: () => runControl.refresh(),
        onDone: (value) => { deltas.flush(); setDone(value); setMessages((rows) => rows.map((message) => message.id === assistantId && message.reasoningStartedAt ? { ...message, reasoningOpen: false, reasoningElapsedMs: Date.now() - message.reasoningStartedAt } : message)) },
      }, abortController.signal)
      deltas.flush()
      setMessages((rows) => rows.map((message) => message.id === assistantId
        ? { ...message, streaming: false, reasoningOpen: false, reasoningElapsedMs: message.reasoningStartedAt ? Date.now() - message.reasoningStartedAt : message.reasoningElapsedMs } : message))
      void loadIndex()
    } catch (reason) {
      deltas.flush()
      if (reason instanceof DOMException && reason.name === 'AbortError') {
        setError(null)
      } else if (reason instanceof ApiError && reason.code === 'CHAT_RUN_CANCELLED') {
        setError(null)
      } else if (reason instanceof ApiError && reason.code === 'CHAT_STREAM_INCOMPLETE') {
        setError(tx('streamDisconnectedRecovering'))
      } else {
        setError(reason instanceof Error ? reason.message : tx('streamFailed'))
      }
      setMessages((rows) => rows.map((message) => message.id === assistantId
        ? { ...message, streaming: false, reasoningOpen: false, reasoningElapsedMs: message.reasoningStartedAt ? Date.now() - message.reasoningStartedAt : message.reasoningElapsedMs } : message))
    } finally {
      deltas.cancel()
      runControl.refresh()
      controller.current = null
      streamOperation.finish(operation)
      setSending(false)
    }
  }

  const removeConversation = async () => {
    if (!selected || sending) return
    setSending(true); setError(null)
    try {
      await deleteConversation(request, selected.id)
      if(linkedConversation.current?.id===selected.id)linkedConversation.current=null
      const remaining = conversations.filter(({ id }) => id !== selected.id)
      setConversations(remaining); setSelectedId(remaining[0]?.id ?? null); setMessages([]); setDeleteConfirm(false)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('conversationDeleteFailed')) }
    finally { setSending(false) }
  }

  const applyConversationSettings = (update: ConversationSettingsUpdate) => {
    setConversations(rows => rows.map(conversation => conversation.id === update.id
      ? { ...conversation, ...(update.title === undefined ? {} : {title:update.title}),
          ...(update.agentId === undefined ? {} : {agentId:update.agentId}), updatedAt:update.updatedAt }
      : conversation))
  }

  const title = useMemo(() => selected?.title || tx('newConversation'), [selected, tx])
  const toggleReasoning = useCallback((messageId: string) => setMessages((rows) => rows.map((message) =>
    message.id === messageId ? { ...message, reasoningOpen: !message.reasoningOpen } : message)), [])

  return (
    <section className={`chat-workspace ${inspectorCollapsed ? 'inspector-collapsed' : ''}`}>
      <WorkspaceNavigation ready={!loading}><aside className="conversation-rail">
        {sending && <small role="status">{tx('finishStreamBeforeSwitch')}</small>}
        <div className="conversation-list">
          {conversations.map((conversation) => <button key={conversation.id} disabled={sending} className={conversation.id === selectedId ? 'active' : ''} onClick={() => { if(streamOperation.isActive()) return; listGeneration.current++;setLoading(false);requestedConversation.current=null;if(linkedConversation.current?.id!==conversation.id)linkedConversation.current=null;setLinkError(false);setError(null);setSelectedId(conversation.id) }}><b>{conversation.title}</b><small>{agents.find(({ id }) => id === conversation.agentId)?.name || tx('noAgent')}</small><span>{new Date(conversation.updatedAt).toLocaleString(language === 'zh' ? 'zh-CN' : language === 'ja' ? 'ja-JP' : 'en-US')}</span></button>)}
          {!loading && !conversations.length && <p>{tx('noConversations')}</p>}
          {conversations.length<conversationTotal&&<button type="button" disabled={loading||loadingMore} onClick={()=>void loadMoreConversations()}>{tx('loadMoreConversations')}</button>}
        </div>
      </aside></WorkspaceNavigation>
      <main className="conversation-main">
        <header><div className="chat-head-left"><button className={`chat-panel-toggle ${navigationCollapsed ? 'is-collapsed' : ''}`} type="button" onClick={onToggleNavigation} aria-label={navigationCollapsed ? 'Expand navigation' : 'Collapse navigation'} aria-controls="application-navigation">{navigationCollapsed ? '⟫' : '⟪'}</button><div><span>{tx('chatNav')}</span><b>{title}</b></div></div><div className="chat-header-actions"><span>{selectedAgent?.name || tx('noAgent')}</span><button className={`chat-panel-toggle ${inspectorCollapsed ? 'is-collapsed' : ''}`} type="button" onClick={toggleInspector} aria-label={tx(inspectorCollapsed ? 'expandInspector' : 'collapseInspector')} aria-expanded={!inspectorCollapsed} aria-controls="chat-run-inspector">{inspectorCollapsed ? '⟪' : '⟫'}</button></div></header>
        {error && <div className="chat-error" role="alert">{error}</div>}
        <ChatRunPanel run={runControl} tx={tx}/>
        <ConversationViewport className="message-log" logRef={messageLogRef} tx={tx}>
          {history.hasOlder&&<button type="button" className="history-load-more" disabled={history.loadingOlder||sending} onClick={()=>void loadOlderMessages()}>{tx('loadOlderMessages')}</button>}
          {messages.map(message => <ConversationMessage key={message.id} message={message} mode="chat" logRef={messageLogRef} tx={tx} toggleReasoning={toggleReasoning} />)}
          {!messages.length && <div className="chat-empty"><b>{tx('startConversation')}</b><span>{availableAgents.length ? tx('startConversationHint') : tx('agentRequired')}</span></div>}
        </ConversationViewport>
        <form className="chat-composer" onSubmit={send}><textarea aria-label={tx('composerPlaceholder')} ref={composerRef} value={draft} onChange={updateDraft} onKeyDown={handleComposerKeyDown} rows={1} placeholder={tx('composerPlaceholder')} maxLength={4000} disabled={!composerEnabled} /><div><div className="composer-route" aria-label={tx('activeContext')}><span>{selectedAgent?.name || tx('noAgent')}</span><span>{(selectedAgent?.modelPoolId ? tx('modelPool') : selectedAgent?.modelId) || '—'}</span></div><div className="composer-actions"><span>{draft.length} / 4000</span>{sending ? <button type="button" disabled={!streamOperation.canStop || streamOperation.stopping} onClick={() => void stop()}>{tx('stop')}</button> : <button className="send" type="submit" disabled={!draft.trim() || !composerEnabled || runControl.active || !runControl.ready}>→</button>}</div></div></form>
      </main>
      <aside className="run-inspector" id="chat-run-inspector" inert={inspectorCollapsed}>
        <ConversationSettings key={selectedId || 'empty'} request={request} conversation={selected}
          agents={agents} locked={sending || runControl.active || !runControl.ready || loading}
          tx={tx} onUpdated={applyConversationSettings} onBusyChange={setSettingsBusy} onDelete={() => setDeleteConfirm(true)} onClose={toggleInspector}
          newConversationAgentId={selectedAgent?.id ?? ''} onNewConversationAgentChange={setNewConversationAgentId}
          hasMoreAgents={agentPages.hasMore} agentsLoading={agentPages.loading} loadMoreAgents={()=>void agentPages.loadMore()}/>
        <details className="conversation-run-details"><summary>{tx('conversationRunDetails')}</summary>
          <section><label>{tx('currentRun')}</label><dl><div><dt>EVENTS</dt><dd>{done?.eventCount ?? runtimeEventCount}</dd></div><div><dt>TOKENS</dt><dd>{done?.totalTokenCount ?? '—'}</dd></div></dl></section>
          <section><label>{tx('runtimeEvents')}</label>{runtimeEvents.slice(-6).map((event,index)=><p key={index}>{event.type}</p>)}</section>
        </details>
      </aside>
      {deleteConfirm && selected && <div className="chat-modal" role="dialog" aria-modal="true"><div className="chat-dialog"><span>{tx('systemConfirmation')}</span><h2>{tx('deleteConversation')}</h2><p>{selected.title}</p><div><button onClick={() => setDeleteConfirm(false)}>{tx('cancel')}</button><button className="danger" disabled={sending} onClick={() => void removeConversation()}>{sending ? tx('processing') : tx('confirmRemove')}</button></div></div></div>}
    </section>
  )
}
