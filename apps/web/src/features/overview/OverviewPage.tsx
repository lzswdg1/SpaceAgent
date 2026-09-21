import { TokenUsageCard } from './TokenUsageCard'
import { AgentUsageCard } from './AgentUsageCard'
import { formatUsageCost, usageCopy } from './usageCopy'
import { listConversations } from '../chat/chatApi'
import type { ConversationSummary } from '../chat/types'
import { platformCopy } from '../../app/platformCopy'
import { Folder, MessageSquare } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import type { Language } from '../../copy'
import type { AuthenticatedRequest, UsageTimeseriesPoint, UsageRange } from './types'
import { stopMonitoringSession, stopMonitoringSessions } from './overviewApi'
import { useOverview } from './useOverview'
import './overview.css'

type Props = {
  language: Language
  request: AuthenticatedRequest
  onNavigate: (path: string) => void
  tx: (key: string) => string
}

const number = new Intl.NumberFormat('en-US', { maximumFractionDigits: 1 })
const formatTokens = (value: number) => value >= 1_000_000
  ? `${number.format(value / 1_000_000)}M`
  : value >= 1_000 ? `${number.format(value / 1_000)}K` : number.format(value)
const formatDuration = (seconds: number) => {
  const minutes = Math.floor(seconds / 60)
  const remainder = seconds % 60
  return minutes ? `${minutes}m ${remainder}s` : `${remainder}s`
}

function points(values: UsageTimeseriesPoint[], width = 600, height = 170) {
  if (!values.length) return ''
  const totals = values.map((value) => value.inputTokens + value.outputTokens)
  const max = Math.max(...totals, 1)
  return totals.map((value, index) => {
    const x = values.length === 1 ? width / 2 : index / (values.length - 1) * width
    const y = height - value / max * (height - 24) - 12
    return `${x},${y}`
  }).join(' ')
}

export function OverviewPage({ language, request, onNavigate, tx }: Props) {
  const [range, setRange] = useState<UsageRange>('7d')
  const [sessionPage, setSessionPage] = useState(1)
  const text = platformCopy(language)
  const usageText = usageCopy(language)
  const [recent, setRecent] = useState<ConversationSummary[]>([])
  const [recentError, setRecentError] = useState(false)
  useEffect(() => {
    let active = true
    listConversations(request).then(page => { if(active) { setRecent(page.items.filter(row => row.status === 'ACTIVE').sort((a,b) => b.updatedAt.localeCompare(a.updatedAt)).slice(0,5)); setRecentError(false) } }).catch(() => { if(active) setRecentError(true) })
    return () => { active = false }
  }, [request])
  const [sessionStopTarget, setSessionStopTarget] = useState<{ ids: string[]; label: string } | null>(null)
  const [stoppingSessions, setStoppingSessions] = useState(false)
  const [sessionError, setSessionError] = useState<string | null>(null)
  const { loading, data, error, refresh } = useOverview(request, range, sessionPage)
  const overview = data?.overview.overview
  const rows = data?.usage.rows ?? []
  const totalTokens = overview
    ? overview.totalInputTokens + overview.totalOutputTokens + overview.totalCacheReadTokens + overview.totalCacheCreateTokens
    : 0
  const chartPoints = useMemo(() => points(data?.overview.timeseries ?? []), [data])
  const sessions = data?.sessions.sessions ?? []
  const activeSessions = sessions.filter(({ isRuntimeActive }) => isRuntimeActive)
  const sessionPages = Math.max(1, Math.ceil((data?.sessions.total ?? 0) / (data?.sessions.pageSize || 10)))
  useEffect(() => {
    if (!loading && data && sessionPage > sessionPages) setSessionPage(sessionPages)
  }, [data, loading, sessionPage, sessionPages])

  const stopSessions = async () => {
    if (!sessionStopTarget || stoppingSessions) return
    setStoppingSessions(true); setSessionError(null)
    try {
      if (sessionStopTarget.ids.length === 1) {
        await stopMonitoringSession(request, sessionStopTarget.ids[0])
      } else {
        const result = await stopMonitoringSessions(request, sessionStopTarget.ids)
        if (result.failedIds.length) setSessionError(tx('sessionBatchStopPartial'))
      }
      setSessionStopTarget(null)
      await refresh()
    } catch (reason) {
      setSessionError(reason instanceof Error ? reason.message : tx('sessionStopFailed'))
    } finally { setStoppingSessions(false) }
  }

  return (
    <section className="overview-page" aria-busy={loading}>
      <header className="overview-page-head">
        <div><h1>{text.title}</h1></div>
        <button className="platform-primary" type="button" onClick={() => onNavigate('/app/chat?new=1')}>＋ {text.newChat}</button>
      </header>
      {(error || sessionError) && <div className="overview-error" role="alert"><span>{sessionError || error}</span><button onClick={() => { setSessionError(null); void refresh() }}>{tx('retry')}</button></div>}
      <div className="overview-metrics">
        <article><span>{tx('registeredAgents')}</span><b>{!data ? '—' : number.format(overview?.totalAgents ?? 0)}</b></article>
        <article><span>{tx('sessions')}</span><b>{!data ? '—' : number.format(overview?.totalSessions ?? 0)}</b></article>
        <article><span>{tx('settledTokens')}</span><b>{!data ? '—' : formatTokens(totalTokens)}</b></article>
        <article><span>{usageText.cost}</span><b>{!data ? '—' : formatUsageCost(overview?.totalCostUsd)}</b><small>{overview?.hasIncompleteCost ? usageText.incomplete : ''}</small></article>
      </div>
      <div className="platform-overview-focus">
        <article className="overview-card resume-card"><header><b>{text.resume}</b></header>
          {recent.map(conversation => <div className="resume-row" key={conversation.id}>{conversation.projectId ? <Folder size={20} aria-hidden="true" /> : <MessageSquare size={20} aria-hidden="true" />}<div><b>{conversation.title}</b><small>{new Date(conversation.updatedAt).toLocaleString(language)}</small></div><button type="button" onClick={() => onNavigate(conversation.projectId ? `/app/project?project=${encodeURIComponent(conversation.projectId)}&directory=${encodeURIComponent(conversation.projectDirectoryId || '')}&conversation=${encodeURIComponent(conversation.id)}` : `/app/chat?conversation=${encodeURIComponent(conversation.id)}`)}>{text.open}</button></div>)}
          {!recent.length && <p>{recentError ? text.resumeError : text.noRecent}</p>}
        </article>
        <TokenUsageCard overview={overview} loading={loading} language={language} range={range} onRange={next => { setRange(next); setSessionPage(1) }} incompleteLabel={usageText.incomplete} />
      </div>
      <AgentUsageCard request={request} language={language} />
      <details className="overview-management"><summary>{text.details}</summary><div className="overview-grid">
        <article className="overview-card usage-card">
          <header><b>{tx('usageByAgent')}</b><span>{rows.length}</span></header>
          <div className="usage-rows">{rows.slice(0, 6).map((row, index) => <div key={row.key}><span>{String(index + 1).padStart(2, '0')}</span><p><b>{row.name}</b><small>{row.callCount} {tx('calls')}</small></p><strong>{formatTokens(row.totalTokens)}</strong></div>)}{!rows.length && <div className="overview-empty">{tx('noUsageEvidence')}</div>}</div>
        </article>
        <article className="overview-card trend-card">
          <header><b>{tx('tokenTrend')}</b><span>{data?.overview.overview.bucketSize ?? '—'}</span></header>
          {chartPoints ? <svg viewBox="0 0 600 170" role="img" aria-label={tx('tokenTrend')}><polyline points={chartPoints} fill="none" stroke="#91a9bb" strokeWidth="2" vectorEffect="non-scaling-stroke" /></svg> : <div className="overview-empty">{tx('noUsageEvidence')}</div>}
        </article>
        <article className="overview-card realtime-card">
          <header><b>{tx('realtime')}</b><span>{data?.realtime.runtimeActive ?? 0} ACTIVE</span></header>
          <dl><div><dt>{tx('runtimeActive')}</dt><dd>{data?.realtime.runtimeActive ?? 0}</dd></div><div><dt>{tx('processing')}</dt><dd>{data?.realtime.processing ?? 0}</dd></div><div><dt>{tx('peakConcurrent')}</dt><dd>{overview?.peakConcurrent ?? 0}</dd></div></dl>
        </article>
        <article className="overview-card sessions-card">
          <header><b>{tx('runtimeSessions')}</b><div><span>{data?.sessions.runtimeActive ?? 0} ACTIVE · {data?.sessions.total ?? 0} TOTAL</span><button type="button" disabled={!activeSessions.length || stoppingSessions} onClick={() => setSessionStopTarget({ ids: activeSessions.map(({ id }) => id), label: tx('allActiveSessions') })}>{tx('stopAllActive')}</button></div></header>
          <div className="session-rows">{sessions.map((session) => <article key={session.id}><span className={session.isRuntimeActive ? 'active' : ''}>{session.isRuntimeActive ? 'LIVE' : session.status.toUpperCase()}</span><div><b>{session.agentName || session.name}</b><small>{session.providerName || session.source} · {session.messageCount} {tx('sessionMessages')}</small></div><dl><div><dt>TOKENS</dt><dd>{formatTokens(session.totalInputTokens + session.totalOutputTokens)}</dd></div><div><dt>{tx('duration')}</dt><dd>{formatDuration(session.durationSeconds)}</dd></div></dl><div className="session-actions"><button type="button" onClick={() => onNavigate(`/app/tracing?session=${encodeURIComponent(session.id)}`)}>{tx('viewTrace')}</button>{session.isRuntimeActive ? <button className="danger" type="button" disabled={stoppingSessions} onClick={() => setSessionStopTarget({ ids: [session.id], label: session.agentName || session.name })}>{tx('stopSession')}</button> : <small>{session.closeReason || new Date(session.lastActivityAt).toLocaleString()}</small>}</div></article>)}{!sessions.length && <div className="overview-empty">{tx('noRuntimeSessions')}</div>}</div>
          <footer className="session-pagination"><button type="button" disabled={loading || sessionPage <= 1} onClick={() => setSessionPage((page) => Math.max(1, page - 1))}>← {tx('previousPage')}</button><span>{sessionPage} / {sessionPages}</span><button type="button" disabled={loading || sessionPage >= sessionPages} onClick={() => setSessionPage((page) => Math.min(sessionPages, page + 1))}>{tx('nextPage')} →</button></footer>
        </article>
      </div></details>
      <footer className="overview-foot">{language === 'zh' ? '费用仅显示后端已确认的价格证据。' : language === 'ja' ? 'コストはバックエンドで確定した価格証跡のみ表示します。' : 'Cost shows only backend-confirmed price evidence.'}</footer>
      {sessionStopTarget && <div className="overview-modal" role="dialog" aria-modal="true"><div><span>{tx('systemConfirmation')}</span><h2>{tx(sessionStopTarget.ids.length > 1 ? 'stopAllSessionsTitle' : 'stopSessionTitle')}</h2><p>{sessionStopTarget.label}</p><small>{tx('stopSessionHint')}</small><footer><button type="button" onClick={() => setSessionStopTarget(null)}>{tx('cancel')}</button><button type="button" className="danger" disabled={stoppingSessions} onClick={() => void stopSessions()}>{stoppingSessions ? tx('processing') : tx('confirm')}</button></footer></div></div>}
    </section>
  )
}
