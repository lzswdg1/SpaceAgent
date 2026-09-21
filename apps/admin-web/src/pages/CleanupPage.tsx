import { errorText } from '../errorText'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { AdminApi } from '../adminApi'
import type { CleanupJobDetail, CleanupJobSummary, CleanupOverview } from '../types'

const stamp = (value: string | null) => value ? new Date(value).toLocaleString() : '—'

export function CleanupPage({ api, tx }: { api: AdminApi; tx: (key: string) => string }) {
  const [items, setItems] = useState<CleanupJobSummary[]>([])
  const [overview, setOverview] = useState<CleanupOverview | null>(null)
  const [detail, setDetail] = useState<CleanupJobDetail | null>(null)
  const [page, setPage] = useState(0)
  const [total, setTotal] = useState(0)
  const [kind, setKind] = useState('')
  const [state, setState] = useState('')
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const version = useRef(0)

  const load = useCallback(async () => {
    const current = ++version.current
    setLoading(true); setError(null)
    try {
      const [jobs, summary] = await Promise.all([
        api.cleanupJobs({ page, pageSize: 25, kind: kind || undefined, state: state || undefined, query: query.trim() || undefined }),
        api.cleanupOverview(),
      ])
      if (current !== version.current) return
      setItems(jobs.items); setTotal(jobs.total); setOverview(summary)
    } catch (reason) {
      if (current === version.current) setError(reason instanceof Error ? reason.message : 'Unable to load cleanup jobs')
    } finally { if (current === version.current) setLoading(false) }
  }, [api, page, kind, state, query])

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), query ? 250 : 0)
    return () => { clearTimeout(timer); version.current += 1 }
  }, [load, query])

  const inspect = async (job: CleanupJobSummary) => {
    setError(null)
    try { setDetail(await api.cleanupJobDetail(job.kind, job.subjectId)) }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Unable to load cleanup detail') }
  }

  const metrics = overview ? [
    ['PENDING', overview.pending], ['CLAIMED', overview.claimed], ['RETRY', overview.retry],
    ['BLOCKED', overview.blocked], ['COMPLETED', overview.completed],
  ] as const : []

  return <section className="page-view">
    <header className="page-head"><div><h1>{tx('cleanupTitle')}</h1><p>{tx('cleanupCopy')}</p></div></header>
    {error && <div className="page-error" role="alert"><span>{errorText(error, tx)}</span><button onClick={() => void load()}>{tx('retry')}</button></div>}
    <div className="metric-strip cleanup-metrics">{metrics.map(([label, value]) => <article className="metric" key={label}><span>{tx(label)}</span><strong>{value}</strong><small>{tx("JOBS")}</small></article>)}</div>
    {overview?.blockers.length ? <section className="surface blocker-summary"><header><b>{tx('blockerSummary')}</b><span>{tx("BLOCKED ONLY")}</span></header><div>{overview.blockers.map((item) => <span key={item.code}><b>{item.code}</b><strong>{item.count}</strong></span>)}</div></section> : null}
    <div className="toolbar cleanup-toolbar"><label className="search-box"><span>⌕</span><input value={query} onChange={(event) => { setPage(0); setQuery(event.target.value) }} placeholder={tx("Subject ID")} aria-label={tx("Subject ID")} /></label><select value={kind} onChange={(event) => { setPage(0); setKind(event.target.value) }}><option value="">{tx("ALL KINDS")}</option><option value="USER">{tx("USER")}</option><option value="ORGANIZATION">{tx("ORGANIZATION")}</option></select><select value={state} onChange={(event) => { setPage(0); setState(event.target.value) }}><option value="">{tx("ALL STATES")}</option><option value="PENDING">{tx("PENDING")}</option><option value="CLAIMED">{tx("CLAIMED")}</option><option value="RETRY">{tx("RETRY")}</option><option value="BLOCKED">{tx("BLOCKED")}</option><option value="COMPLETED">{tx("COMPLETED")}</option></select></div>
    <div className="data-surface"><div className="data-table cleanup-table"><div className="data-row heading"><span>{tx("SUBJECT")}</span><span>{tx("STATE")}</span><span>{tx("PROGRESS")}</span><span>{tx("CURRENT STEP")}</span><span>{tx("NEXT / UPDATED")}</span></div>{!error && items.map((job) => <button className="data-row" key={`${tx(job.kind)}:${job.subjectId}`} onClick={() => void inspect(job)}><span className="identity"><i>{job.kind.slice(0, 2)}</i><span><b>{job.kind}</b><small>{job.subjectId}</small></span></span><span className={`state ${job.state === 'COMPLETED' ? 'good' : job.state === 'BLOCKED' ? 'bad' : 'warn'}`}>{tx(job.state)}</span><span>{job.completedSteps}/{job.totalSteps} · {job.attempt}/{job.maxAttempts}</span><span>{job.currentStepKey || '—'}{job.lastErrorCode && <small className="bad"> · {job.lastErrorCode}</small>}</span><span>{stamp(job.nextAttemptAt)}<small> · {stamp(job.updatedAt)}</small></span></button>)}{!loading && !error && !items.length && <div className="empty-state">{tx('noCleanupJobs')}</div>}</div><footer className="pagination"><span>{loading || error ? tx(error ? 'dataUnavailable' : 'loading') : `${page * 25 + (items.length ? 1 : 0)}–${page * 25 + items.length} / ${total}`}</span><div><button disabled={page === 0 || loading} onClick={() => setPage((value) => value - 1)}>← {tx('previous')}</button><button disabled={(page + 1) * 25 >= total || loading} onClick={() => setPage((value) => value + 1)}>{tx('next')} →</button></div></footer></div>
    <div className={`drawer-layer ${detail ? 'open' : ''}`}><button className="drawer-scrim" aria-label={tx("Close")} onClick={() => setDetail(null)} /><aside className="drawer" role="dialog" aria-modal="true"><header><span>{tx("CLEANUP /")}{detail?.job.kind || '—'}</span><button aria-label={tx("Close")} onClick={() => setDetail(null)}>×</button></header>{detail && <><div className="profile"><div><h2>{detail.job.subjectId}</h2><p><span className={detail.job.state === 'BLOCKED' ? 'bad' : detail.job.state === 'COMPLETED' ? 'good' : 'warn'}>{tx(detail.job.state)}</span>{tx("· REV")}{detail.job.revision}</p></div></div><div className="detail-grid"><div><span>{tx("PROGRESS")}</span><b>{detail.job.completedSteps}/{detail.job.totalSteps}</b></div><div><span>{tx("ATTEMPT")}</span><b>{detail.job.attempt}/{detail.job.maxAttempts}</b></div><div><span>{tx("NEXT ATTEMPT")}</span><b>{stamp(detail.job.nextAttemptAt)}</b></div><div><span>{tx("COMMAND")}</span><b>{detail.job.commandId || '—'}</b></div></div>{detail.job.lastErrorCode && <div className="form-error"><b>{detail.job.lastErrorCode}</b><span>{detail.job.lastErrorSummary}</span></div>}<section className="drawer-section"><header><b>{tx("ORDERED STEPS")}</b><span>{detail.steps.length}</span></header><div className="cleanup-steps">{detail.steps.map((step) => <div key={step.stepKey}><i className={step.state.toLowerCase()} /><span><b>{step.sequence} / {step.stepKey}</b><small>{tx(step.state)}{tx("· attempt")}{step.attempt}{step.lastErrorCode ? ` · ${step.lastErrorCode}` : ''}</small></span></div>)}</div></section><p className="contract-note">{tx('cleanupNoBlindRetry')}</p></>}</aside></div>
  </section>
}
