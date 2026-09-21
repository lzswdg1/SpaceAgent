import { useCallback, useEffect, useMemo, useState } from 'react'
import type { AuthenticatedRequest } from '../overview/types'
import { groupConversationTraces, loadTraceDetail, loadTracing } from './tracingApi'
import type { ConversationTraceGroup, TraceDetail, TraceStats } from './types'
import './tracing.css'
import { SkillEvidence } from './SkillEvidence'

type Props = { request: AuthenticatedRequest; initialSessionId?: string | null; tx: (key: string) => string }
const emptyStats: TraceStats = { totalTraces: 0, successTraces: 0, errorTraces: 0, abortedTraces: 0, avgDurationMs: 0, p50DurationMs: 0, p95DurationMs: 0, avgFirstTokenMs: 0, totalTokens: 0, totalCostUsd: null, range: '7d' }
const duration = (value: number | null) => value == null ? '—' : value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(2)} s`

export function TracingPage({ request, initialSessionId, tx }: Props) {
  const [allGroups, setAllGroups] = useState<ConversationTraceGroup[]>([])
  const [stats, setStats] = useState<TraceStats>(emptyStats)
  const [mode, setMode] = useState<'chat' | 'project'>('chat')
  const [status, setStatus] = useState('ALL')
  const [search, setSearch] = useState('')
  const [sessionId, setSessionId] = useState<string | null>(initialSessionId || null)
  const [traceId, setTraceId] = useState<string | null>(null)
  const [detail, setDetail] = useState<TraceDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true); setError(null)
    try {
      const result = await loadTracing(request)
      setAllGroups(groupConversationTraces(result.list.traces)); setStats(result.stats)
    } catch (reason) { setError(reason instanceof Error ? reason.message : tx('traceLoadFailed')) }
    finally { setLoading(false) }
  }, [request, tx])
  useEffect(() => { void load() }, [load])

  const groups = useMemo(() => allGroups.filter((group) => {
    const modeMatch = group.mode === mode
    const textMatch = `${group.sessionName} ${group.traces.map(({ agentName }) => agentName).join(' ')}`.toLowerCase().includes(search.trim().toLowerCase())
    const statusMatch = status === 'ALL' || group.traces.some((trace) => trace.status === status)
    return modeMatch && textMatch && statusMatch
  }), [allGroups, mode, search, status])

  useEffect(() => {
    if (!initialSessionId) return
    const target = allGroups.find((group) => group.sessionId === initialSessionId)
    if (!target) return
    setMode(target.mode)
    setSessionId(target.sessionId)
    setTraceId(target.traces[0]?.id ?? null)
  }, [allGroups, initialSessionId])

  const selectedGroup = groups.find((group) => group.sessionId === sessionId) ?? groups[0] ?? null
  const selectedTrace = selectedGroup?.traces.find(({ id }) => id === traceId) ?? selectedGroup?.traces[0] ?? null

  useEffect(() => {
    if (selectedGroup && selectedGroup.sessionId !== sessionId) setSessionId(selectedGroup.sessionId)
    if (!selectedGroup && sessionId) setSessionId(null)
  }, [selectedGroup, sessionId])
  useEffect(() => {
    if (selectedTrace && selectedTrace.id !== traceId) setTraceId(selectedTrace.id)
    if (!selectedTrace) { setTraceId(null); setDetail(null); return }
    let active = true
    loadTraceDetail(request, selectedTrace.id).then((value) => active && setDetail(value)).catch((reason) => active && setError(reason instanceof Error ? reason.message : tx('traceDetailFailed')))
    return () => { active = false }
  }, [request, selectedTrace, traceId, tx])

  const maxSpanDuration = Math.max(1, ...(detail?.spans.map((span) => span.durationMs ?? 0) ?? [1]))

  return <section className="trace-console">
    <header className="trace-heading"><div><span>OPERATE / CONVERSATION TRACE</span><h1>{tx('conversationTracing')}</h1><p>{tx('conversationTracingHint')}</p></div><small>7 DAYS</small></header>
    {error && <div className="trace-error"><span>{error}</span><button onClick={() => setError(null)}>×</button></div>}
    <section className="trace-stats"><div><span>{tx('conversations')}</span><b>{allGroups.length}</b></div><div><span>{tx('successfulRuns')}</span><b>{stats.successTraces}</b></div><div><span>{tx('errorRuns')}</span><b>{stats.errorTraces}</b></div><div><span>P95</span><b>{duration(stats.p95DurationMs)}</b></div><div><span>TOKENS</span><b>{stats.totalTokens.toLocaleString()}</b></div></section>
    <section className="trace-toolbar"><div className="trace-modes"><button className={mode === 'chat' ? 'active' : ''} onClick={() => setMode('chat')}>CHAT</button><button className={mode === 'project' ? 'active' : ''} onClick={() => setMode('project')}>PROJECT</button></div><div className="trace-search"><span>⌕</span><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder={tx('searchConversations')} /></div><select value={status} onChange={(event) => setStatus(event.target.value)}><option value="ALL">{tx('allStatuses')}</option><option value="SUCCESS">SUCCESS</option><option value="ERROR">ERROR</option><option value="ABORTED">ABORTED</option><option value="RUNNING">RUNNING</option></select></section>
    <div className="trace-layout">
      <aside className="trace-conversations"><header><b>{tx('conversations')}</b><span>{groups.length.toString().padStart(2,'0')}</span></header><div>{groups.map((group) => <button key={group.sessionId} className={group.sessionId === selectedGroup?.sessionId ? 'active' : ''} onClick={() => { setSessionId(group.sessionId); setTraceId(group.traces[0]?.id ?? null) }}><span><b>{group.sessionName}</b><small>{group.mode.toUpperCase()} · {group.traces.length} RUNS</small></span><em>{group.totalTokens.toLocaleString()} T</em></button>)}{!loading && !groups.length && <p>{tx('noConversationTraces')}</p>}</div></aside>
      <main className="trace-detail"><header><div><span>{selectedGroup?.mode.toUpperCase() || '—'} / {selectedGroup?.sessionId.slice(0,8) || '—'}</span><b>{selectedGroup?.sessionName || tx('conversationTracing')}</b></div><strong>{selectedTrace?.status || '—'}</strong></header>{selectedTrace ? <><section className="trace-run-summary"><div><span>{tx('agent')}</span><b>{selectedTrace.agentName || selectedTrace.agentId}</b></div><div><span>{tx('duration')}</span><b>{duration(selectedTrace.durationMs)}</b></div><div><span>TOKENS</span><b>{selectedTrace.totalTokens.toLocaleString()}</b></div><div><span>{tx('toolCalls')}</span><b>{selectedTrace.toolCalls}</b></div></section><SkillEvidence key={selectedTrace.id} runId={selectedTrace.id} request={request} tx={tx} /><section className="trace-waterfall"><header><b>{tx('spanWaterfall')}</b><span>{detail?.spans.length ?? 0} SPANS</span></header>{detail?.spans.map((span, index) => <article key={span.id} className={span.isError ? 'error' : ''}><div className="span-name"><small>{String(index + 1).padStart(2,'0')} / {span.spanType}</small><b>{span.name}</b></div><div className="span-track"><i style={{ width: `${Math.max(3, ((span.durationMs ?? 0) / maxSpanDuration) * 100)}%` }} /></div><em>{duration(span.durationMs)}</em></article>)}{!detail && <div className="trace-loading">{tx('loadingTrace')}</div>}</section></> : <div className="trace-empty">{tx('selectConversationTrace')}</div>}</main>
      <aside className="trace-runs"><header><b>{tx('conversationRuns')}</b><span>{selectedGroup?.traces.length ?? 0}</span></header><div>{selectedGroup?.traces.map((trace, index) => <button key={trace.id} className={trace.id === selectedTrace?.id ? 'active' : ''} onClick={() => setTraceId(trace.id)}><span><b>RUN {String(selectedGroup.traces.length - index).padStart(2,'0')}</b><small>{new Date(trace.startTime).toLocaleString()}</small></span><span><em>{trace.status}</em><strong>{duration(trace.durationMs)}</strong></span></button>)}</div>{selectedTrace && <section className="trace-evidence"><label>{tx('evidence')}</label><dl><div><dt>LLM</dt><dd>{duration(selectedTrace.llmMs)}</dd></div><div><dt>TOOLS</dt><dd>{duration(selectedTrace.toolWallMs)}</dd></div><div><dt>INPUT</dt><dd>{selectedTrace.inputTokens}</dd></div><div><dt>OUTPUT</dt><dd>{selectedTrace.outputTokens}</dd></div></dl>{selectedTrace.costUsd == null ? <small>{tx('noSettledCost')}</small> : <strong>${selectedTrace.costUsd.toFixed(4)}{selectedTrace.costEstimated ? ` · ${tx('estimated')}` : ''}</strong>}</section>}</aside>
    </div>
  </section>
}
