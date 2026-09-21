import { errorText } from '../errorText'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { AdminApi } from '../adminApi'
import type { AuditView } from '../types'
import { shortEvidenceId } from '../adminViewModel'

export function AuditPage({ api, tx }: { api: AdminApi; tx: (key: string) => string }) {
  const [items, setItems] = useState<AuditView[]>([])
  const [page, setPage] = useState(0)
  const [total, setTotal] = useState(0)
  const [action, setAction] = useState('')
  const [target, setTarget] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const requestVersion = useRef(0)

  const load = useCallback(async () => {
    const version = ++requestVersion.current
    setLoading(true)
    setError(null)
    try {
      const result = await api.auditEvents({
        page,
        pageSize: 25,
        action: action.trim() || undefined,
        target: target.trim() || undefined,
      })
      if (version !== requestVersion.current) return
      setItems(result.items)
      setTotal(result.total)
    } catch (reason) {
      if (version === requestVersion.current) {
        setError(reason instanceof Error ? reason.message : 'Unable to load audit evidence')
      }
    } finally {
      if (version === requestVersion.current) setLoading(false)
    }
  }, [action, api, page, target])

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), action || target ? 250 : 0)
    return () => {
      clearTimeout(timer)
      requestVersion.current += 1
    }
  }, [action, load, target])

  return <section className="page-view">
    <header className="page-head"><div><h1>{tx('auditTitle')}</h1><p>{tx('auditCopy')}</p></div></header>
    {error && <div className="page-error" role="alert"><span>{errorText(error, tx)}</span><button onClick={() => void load()}>{tx('retry')}</button></div>}
    <div className="toolbar two-search">
      <label className="search-box"><span>⌕</span><input aria-label={tx("Audit action")} value={action} onChange={(event) => { setPage(0); setAction(event.target.value) }} placeholder={tx("Action")} /></label>
      <label className="search-box"><span>⌕</span><input aria-label={tx("Audit target ID")} value={target} onChange={(event) => { setPage(0); setTarget(event.target.value) }} placeholder={tx("Target ID")} /></label>
    </div>
    <div className="data-surface"><div className="data-table audit-table"><div className="data-row heading"><span>{tx("ACTION")}</span><span>{tx("TARGET")}</span><span>{tx("OUTCOME")}</span><span>{tx("ACTOR")}</span><span>{tx("OCCURRED")}</span></div>
      {!error && items.map((item) => <div className="data-row" key={item.id}>
        <span className="identity"><i>•</i><span><b>{tx(item.action)}</b><small>{item.requestId || '—'}</small></span></span>
        <span>{tx(item.targetType || 'SYSTEM')} / {item.targetId?.slice(0, 8) || '—'}</span>
        <span className={`state ${item.outcome === 'SUCCEEDED' ? 'good' : item.outcome === 'UNKNOWN' ? 'warn' : 'bad'}`}>{tx(item.outcome)}</span>
        <span>{shortEvidenceId(item.actorId, tx('noActor'))}</span>
        <span>{new Date(item.occurredAt).toLocaleString()}</span>
      </div>)}
      {!loading && !error && !items.length && <div className="empty-state">{tx("No audit evidence")}</div>}
    </div><footer className="pagination"><span>{loading || error ? tx(error ? 'dataUnavailable' : 'loading') : `${page * 25 + (items.length ? 1 : 0)}–${page * 25 + items.length} / ${total}`}</span><div><button disabled={page === 0 || loading} onClick={() => setPage((value) => value - 1)}>← {tx('previous')}</button><button disabled={(page + 1) * 25 >= total || loading} onClick={() => setPage((value) => value + 1)}>{tx('next')} →</button></div></footer></div>
  </section>
}
